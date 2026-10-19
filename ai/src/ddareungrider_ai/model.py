"""Train-only statistics, chronological holdouts and a shared LightGBM count model."""
from pathlib import Path
import json
import platform

import lightgbm as lgb
import numpy as np
import pandas as pd

FEATURES = ["station_code", "weekday", "slot", "month"]
LEVELS = [["station_id", "weekday", "slot"], ["station_id", "weekday", "hour"], ["station_id"], []]


class TypicalDemand:
    def __init__(self, min_days=3):
        self.min_days = min_days
        self.tables = []

    def fit(self, frame):
        self.tables = []
        for keys in LEVELS:
            grouped = frame.assign(_global=0, _date=frame.timestamp.dt.normalize()).groupby(keys or ["_global"], observed=True)
            table = grouped.agg(median=("value", "median"), samples=("value", "size"), days=("_date", "nunique"))
            table["q25"] = grouped.value.quantile(.25)
            table["q75"] = grouped.value.quantile(.75)
            table = table.reset_index()
            self.tables.append(table)
        return self

    def predict(self, frame):
        result = pd.DataFrame(index=range(len(frame)), columns=["median", "q25", "q75", "samples", "days", "level"])
        remaining = np.ones(len(frame), dtype=bool)
        for level, (keys, table) in enumerate(zip(LEVELS, self.tables)):
            candidates = frame.reset_index(drop=True).assign(_global=0).merge(table, on=keys or ["_global"],
                                                                            how="left", sort=False)
            use = remaining & candidates["median"].notna().to_numpy()
            if keys:
                use &= candidates.days.fillna(0).ge(self.min_days).to_numpy()
            for column in ["median", "q25", "q75", "samples", "days"]:
                result.loc[use, column] = candidates.loc[use, column].to_numpy()
            result.loc[use, "level"] = level
            remaining &= ~use
        return result

    def save(self, path):
        path.write_text(json.dumps({"min_days": self.min_days, "tables": [t.to_dict("records") for t in self.tables]}), encoding="utf-8")

    @classmethod
    def load(cls, path):
        data = json.loads(path.read_text(encoding="utf-8"))
        result = cls(data["min_days"])
        result.tables = [pd.DataFrame(rows) for rows in data["tables"]]
        return result


def chronological_split(frame, validation_days=7, test_days=7, min_train_days=14):
    dates = sorted(frame.timestamp.dt.normalize().unique())
    if min(validation_days, test_days, min_train_days) < 1:
        raise ValueError("Split sizes must be positive")
    if len(dates) < validation_days + test_days + min_train_days:
        raise ValueError(f"Insufficient observed days: {len(dates)}; need at least "
                         f"{validation_days + test_days + min_train_days}. Collect more or use --smoke-test.")
    validation_start, test_start = dates[-validation_days-test_days], dates[-test_days]
    train = frame.loc[frame.timestamp < validation_start].copy()
    validation = frame.loc[(frame.timestamp >= validation_start) & (frame.timestamp < test_start)].copy()
    test = frame.loc[frame.timestamp >= test_start].copy()
    # Also usable with future inventory labels: labels must be known before the next split.
    if "label_time" in frame:
        train = train.loc[train.label_time < validation_start]
        validation = validation.loc[validation.label_time < test_start]
    if any(part.empty for part in (train, validation, test)):
        raise ValueError("Empty chronological split after purging future labels")
    return train, validation, test


def matrix(frame, stations):
    result = frame.copy()
    result["station_code"] = result.station_id.map(stations).fillna(-1).astype("int32")
    return result[FEATURES]


def metrics(frame, predictions, peak_threshold):
    absolute = np.abs(frame.value.to_numpy() - np.asarray(predictions, dtype=float))
    detail = frame[["station_id", "weekday", "month"]].reset_index(drop=True).assign(error=absolute)
    peak = frame.value.to_numpy() >= peak_threshold
    return {"mae": float(absolute.mean()), "station_macro_mae": float(detail.groupby("station_id").error.mean().mean()),
            "peak_mae": float(absolute[peak].mean()) if peak.any() else None,
            "weekday_mae": {str(k): float(v) for k, v in detail.groupby("weekday").error.mean().items()},
            "month_mae": {str(k): float(v) for k, v in detail.groupby("month").error.mean().items()}}


def train(frame, manifest, output: Path, smoke=False, half_life_days=0):
    if output.exists() and any(output.iterdir()):
        raise ValueError("Use a new output directory to preserve previous experiments")
    output.mkdir(parents=True, exist_ok=True)
    train_set, valid, test = chronological_split(frame, 1 if smoke else 7, 1 if smoke else 7, 1 if smoke else 14)
    baseline = TypicalDemand().fit(train_set)
    station_codes = {station: index for index, station in enumerate(sorted(train_set.station_id.unique()))}
    weights = None
    if half_life_days < 0:
        raise ValueError("Half-life cannot be negative")
    if half_life_days:
        age = (train_set.timestamp.max() - train_set.timestamp).dt.total_seconds() / 86400
        weights = np.power(.5, age / half_life_days)
    dataset = lgb.Dataset(matrix(train_set, station_codes), label=train_set.value, weight=weights,
                          categorical_feature=["station_code"])
    validation = lgb.Dataset(matrix(valid, station_codes), label=valid.value, reference=dataset)
    model = lgb.train({"objective": "poisson", "metric": "mae", "learning_rate": .05,
                       "num_leaves": 31, "min_data_in_leaf": 50, "verbosity": -1,
                       "num_threads": 4, "seed": 42, "deterministic": True, "force_col_wise": True},
                      dataset, num_boost_round=30 if smoke else 300, valid_sets=[validation],
                      callbacks=[lgb.early_stopping(20, verbose=False)])
    threshold = float(train_set.value.quantile(.9))
    results = {}
    for name, part in [("validation", valid), ("test", test)]:
        base_pred = baseline.predict(part)["median"].astype(float).to_numpy()
        tree_pred = np.maximum(0, model.predict(matrix(part, station_codes)))
        results[name] = {"baseline": metrics(part, base_pred, threshold), "lightgbm": metrics(part, tree_pred, threshold)}
        if name == "test":
            part[["station_id", "timestamp", "value"]].assign(baseline=base_pred, lightgbm=tree_pred).to_csv(
                output / "test-predictions.csv.gz", index=False)
            station_errors = part[["station_id"]].assign(
                baseline_absolute_error=np.abs(part.value.to_numpy()-base_pred),
                lightgbm_absolute_error=np.abs(part.value.to_numpy()-tree_pred))
            station_errors.groupby("station_id").agg(
                baseline_mae=("baseline_absolute_error", "mean"),
                lightgbm_mae=("lightgbm_absolute_error", "mean"),
                observations=("baseline_absolute_error", "size")).to_csv(output / "station-metrics.csv")
    chosen = "lightgbm" if results["validation"]["lightgbm"]["mae"] < results["validation"]["baseline"]["mae"] else "baseline"
    baseline.save(output / "baseline.json")
    # Native Windows LightGBM file I/O cannot reliably open Korean workspace paths.
    (output / "lightgbm.txt").write_text(model.model_to_string(), encoding="utf-8")
    (output / "source-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    report = {"task": "typical_station_demand", "unit": "rides_per_10_minutes", "target": str(frame.target.iloc[0]),
              "status": "smoke_test_only" if smoke else "offline_experiment", "production_ready": False,
              "selected_by_validation": chosen, "station_codes": station_codes, "features": FEATURES,
              "half_life_days": half_life_days, "historical_range_is_prediction_interval": False,
              "data_coverage": {"requested_start": manifest.get("start"), "requested_end": manifest.get("end"),
                                "excluded_days": manifest.get("excluded_days", []),
                                "cohort_stations": len(station_codes), "observed_days": int(frame.timestamp.dt.normalize().nunique())},
              "zero_fill_active_days": manifest["zero_fill_active_days"],
              "missing_policy": "infer_zero_only_on_active_station_days_in_complete_files" if manifest["zero_fill_active_days"] else "observed_bins_only_conditional_on_activity",
              "splits": {name: {"first": str(part.timestamp.min()), "last": str(part.timestamp.max()),
                                  "days": int(part.timestamp.dt.normalize().nunique()), "rows": len(part),
                                  "zero_fraction": float(part.value.eq(0).mean()),
                                  "inferred_zero_fraction": float(part.inferred_zero.mean()) if "inferred_zero" in part else None}
                         for name, part in [("train", train_set), ("validation", valid), ("test", test)]},
              "metrics": results, "peak_threshold_from_train": threshold,
              "versions": {"python": platform.python_version(), "lightgbm": lgb.__version__, "pandas": pd.__version__, "numpy": np.__version__},
              "limitations": ["Activity does not prove full-day operation; validate zero assumptions before deployment.",
                              "Demand is not inventory, a rental probability or return capacity.",
                              "Holiday/weather effects and station relocation history are not modeled.",
                              "Model selection uses validation only; keep test untouched when tuning."]}
    (output / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    return report


def predict(directory: Path, station: str, weekday: int, hour: int, minute: int, month: int):
    if not (0 <= weekday <= 6 and 0 <= hour <= 23 and minute in range(0, 60, 10) and 1 <= month <= 12):
        raise ValueError("Use weekday 0..6 (Monday=0), hour 0..23, minute 0/10/20/30/40/50, month 1..12")
    report = json.loads((directory / "report.json").read_text(encoding="utf-8"))
    frame = pd.DataFrame([dict(station_id=station, weekday=weekday, slot=hour*6+minute//10, hour=hour, month=month)])
    stats = TypicalDemand.load(directory / "baseline.json").predict(frame).iloc[0]
    method = report["selected_by_validation"]
    known = station in report["station_codes"]
    if not known:
        method = "baseline"
    value = float(stats["median"])
    if method == "lightgbm":
        model_text = (directory / "lightgbm.txt").read_text(encoding="utf-8")
        value = max(0., float(lgb.Booster(model_str=model_text).predict(matrix(frame, report["station_codes"]))[0]))
    return {"station_id": station, "target": report["target"], "expected_rides_per_10min": value,
            "method": method, "known_station": known, "baseline_fallback_level": int(stats.level),
            "historical_q25": float(stats.q25), "historical_q75": float(stats.q75), "observed_days": int(stats.days),
            "range_meaning": "historical middle 50%, not a prediction interval", "status": report["status"],
            "production_ready": False, "missing_policy": report["missing_policy"],
            "trained_through": report["splits"]["train"]["last"]}
