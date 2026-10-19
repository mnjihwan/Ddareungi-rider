import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from ddareungrider_ai.data import features, inventory_labels, load_partitions, export_od
from ddareungrider_ai.model import TypicalDemand, chronological_split, train, predict
from ddareungrider_ai.archive import choose, discover


def history(days=30):
    timestamps = pd.date_range("2026-08-01", periods=days, freq="D")
    return features(pd.DataFrame([dict(station_id=station, timestamp=stamp+pd.Timedelta(minutes=slot*10),
                                      target="rentals", value=float(slot%3), inferred_zero=False)
                                 for stamp in timestamps for station in ["ST-1", "ST-2"] for slot in range(6)]))


def test_time_split_and_future_label_purge():
    frame = history()
    frame["label_time"] = frame.timestamp + pd.Timedelta(days=1)
    a, b, c = chronological_split(frame)
    assert a.label_time.max() < b.timestamp.min()
    assert b.label_time.max() < c.timestamp.min()
    assert set(a.timestamp.dt.date).isdisjoint(c.timestamp.dt.date)


def test_insufficient_days_rejected():
    with pytest.raises(ValueError, match="Insufficient"):
        chronological_split(history(5))


def test_statistics_do_not_see_future_and_unknown_falls_back(tmp_path):
    frame = history()
    a, b, _ = chronological_split(frame)
    baseline = TypicalDemand().fit(a)
    before = baseline.predict(b)["median"].to_numpy()
    b["value"] = 999999
    np.testing.assert_array_equal(before, baseline.predict(b)["median"].to_numpy())
    baseline.save(tmp_path / "base.json")
    restored = TypicalDemand.load(tmp_path / "base.json")
    unknown = b.iloc[:1].assign(station_id="new-station")
    assert int(restored.predict(unknown).iloc[0].level) == 3


def test_inventory_exact_labels_and_no_gap_shift():
    frame = pd.DataFrame({"station_id": ["ST-1"]*3,
                          "observed_slot": ["2026-10-01 10:00", "2026-10-01 10:20", "2026-10-01 11:00"],
                          "collected_at": ["2026-10-01 10:01", "2026-10-01 10:21", "2026-10-01 11:01"],
                          "available_bikes": [2, 0, 8]})
    result = inventory_labels(frame, 15)
    assert len(result) == 1 and result.iloc[0].value == 0
    assert result.iloc[0].timestamp == pd.Timestamp("2026-10-01 10:05")
    frame.loc[0, "collected_at"] = "2026-10-01 10:20"
    assert inventory_labels(frame, 15).empty


def test_duplicate_inventory_rejected():
    row = dict(station_id="ST-1", observed_slot="2026-10-01", collected_at="2026-10-01", available_bikes=1)
    with pytest.raises(ValueError, match="Duplicate"):
        inventory_labels(pd.DataFrame([row, row]))


def test_real_model_serialization_and_prediction(tmp_path):
    output = (tmp_path / "따릉 모델").resolve()
    result = train(history(5), {"zero_fill_active_days": False}, output, smoke=True)
    assert result["status"] == "smoke_test_only"
    assert not result["production_ready"]
    # Exercise native model reloading even when validation selected the statistical baseline.
    import lightgbm as lgb
    model = lgb.Booster(model_str=(output / "lightgbm.txt").read_text(encoding="utf-8"))
    assert model.num_trees() >= 1
    prediction = predict(output, "ST-1", 1, 0, 10, 8)
    assert prediction["expected_rides_per_10min"] >= 0
    assert prediction["historical_q25"] <= prediction["historical_q75"]
    unknown = predict(output, "ST-999", 1, 0, 10, 8)
    assert unknown["method"] == "baseline" and not unknown["known_station"]


def test_catalog_uses_posted_ids_and_deduplicates_daily_monthly():
    html = '<span title="tpss_bcycl_od_statnhm_202609.zip" onclick="downloadFile(\'123\')">'
    files = discover(html)
    assert files[0]["sequence"] == "123"
    files.append(dict(filename="daily.zip", period="20260903", sequence="456"))
    assert len(choose(files, pd.Timestamp("2026-09-01").date(), pd.Timestamp("2026-09-30").date())) == 1
    with pytest.raises(ValueError):
        choose(files, pd.Timestamp("2021-01-01").date(), pd.Timestamp("2026-09-30").date())


def test_training_reads_only_manifest_partitions_and_enforces_limit(tmp_path):
    frame = history(1)
    frame.to_csv(tmp_path / "od-2026-08-01.csv.gz", index=False)
    (tmp_path / "manifest.json").write_text(json.dumps({"days": [{"date": "2026-08-01"}]}))
    (tmp_path / "od-2099-01-01.csv.gz").write_text("invalid unused stale data")
    result, _ = load_partitions(tmp_path, "rentals", 1)
    assert result.station_id.nunique() == 1
    with pytest.raises(ValueError, match="row limit"):
        load_partitions(tmp_path, "rentals", 2, 1)


def test_export_missing_day_is_not_zero_filled_and_marks_inferred_zeros(tmp_path, monkeypatch):
    import ddareungrider_ai.data as source
    def fake_query(conn, sql, params):
        if str(params[0]) == "2026-09-02":
            return pd.DataFrame()
        if "actual_rows" in sql:
            return pd.DataFrame([dict(source_file="day.csv", actual_rows=2, imported_rows=2, slots=288, bases=2)])
        return pd.DataFrame([dict(station_id="ST-1", target="rentals", slot=0, value=3),
                             dict(station_id="ST-1", target="returns", slot=1, value=0)])
    monkeypatch.setattr(source, "query", fake_query)
    report = export_od(None, pd.Timestamp("2026-09-01").date(), pd.Timestamp("2026-09-02").date(), tmp_path, True)
    assert report["excluded_days"] == ["2026-09-02"]
    result = pd.read_csv(tmp_path / "od-2026-09-01.csv.gz")
    assert len(result) == 288 and result.inferred_zero.sum() == 286
    assert result.loc[~result.inferred_zero, "value"].sum() == 3
    assert not (tmp_path / "od-2026-09-02.csv.gz").exists()


def test_partial_import_rejected_and_observed_mode_keeps_only_observations(tmp_path, monkeypatch):
    import ddareungrider_ai.data as source
    def fake_query(conn, sql, params):
        if "actual_rows" in sql:
            return pd.DataFrame([dict(source_file="day.csv", actual_rows=1, imported_rows=2, slots=288, bases=2)])
        raise AssertionError("Incomplete file must not be exported")
    monkeypatch.setattr(source, "query", fake_query)
    day = pd.Timestamp("2026-09-01").date()
    report = export_od(None, day, day, tmp_path, False)
    assert report["days"] == [] and report["excluded_days"] == [str(day)]
