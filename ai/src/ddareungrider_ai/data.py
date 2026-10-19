"""Read-only MySQL exports. SQL aggregates OD before transferring it to Python."""
from contextlib import contextmanager
from datetime import date, timedelta
from pathlib import Path
import json
import os

import numpy as np
import pandas as pd
import pymysql


@contextmanager
def database(env_file: Path):
    config = {}
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            config[key.strip()] = value.strip().strip('"').strip("'")
    config.update({k: v for k, v in os.environ.items() if k.startswith("MYSQL_") and v})
    conn = pymysql.connect(host=config.get("MYSQL_HOST", "127.0.0.1"),
                           port=int(config.get("MYSQL_PORT", "3307")),
                           user=config.get("MYSQL_USER", "ddareungi"), password=config["MYSQL_PASSWORD"],
                           database=config.get("MYSQL_DATABASE", "ddareungi"), charset="utf8mb4",
                           cursorclass=pymysql.cursors.DictCursor, autocommit=False,
                           connect_timeout=10, read_timeout=300)
    try:
        with conn.cursor() as cursor:
            cursor.execute("SET time_zone = '+09:00'")
            cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
        yield conn
    finally:
        conn.rollback()
        conn.close()


def query(conn, sql, params=()):
    with conn.cursor() as cursor:
        cursor.execute(sql, params)
        return pd.DataFrame(cursor.fetchall())


def features(frame):
    result = frame.copy()
    result["timestamp"] = pd.to_datetime(result["timestamp"], errors="raise")
    result["weekday"] = result.timestamp.dt.dayofweek
    result["month"] = result.timestamp.dt.month
    result["slot"] = (result.timestamp.dt.hour * 60 + result.timestamp.dt.minute) // 10
    result["hour"] = result.timestamp.dt.hour
    return result


def export_od(conn, start: date, end: date, output: Path, zero_fill=False):
    """Partition by day. Missing source days remain absent, never synthetic zero days."""
    output.mkdir(parents=True, exist_ok=True)
    report = {"source": "OA-21229", "timezone": "Asia/Seoul", "start": str(start), "end": str(end),
              "zero_fill_active_days": zero_fill, "days": [], "excluded_days": []}
    for offset in range((end - start).days + 1):
        day = start + timedelta(days=offset)
        # Require the original import transaction's count, not just presence of one row.
        audit = query(conn, """SELECT f.source_file, COUNT(*) AS actual_rows,
             COUNT(DISTINCT f.time_slot) AS slots, COUNT(DISTINCT f.aggregation_basis) AS bases,
             MAX(m.row_count) AS imported_rows, MAX(m.checksum_sha256) AS source_sha256 FROM station_flow_5m f
             LEFT JOIN data_import_manifest m ON m.source_file=f.source_file
             WHERE f.service_date=%s GROUP BY f.source_file""", (day,))
        if (len(audit) != 1 or audit.imported_rows.isna().any()
                or int(audit.iloc[0].actual_rows) != int(audit.iloc[0].imported_rows)
                or int(audit.iloc[0].slots) != 288 or int(audit.iloc[0].bases) != 2):
            report["excluded_days"].append(str(day))
            # Re-export into a dedicated empty directory to avoid stale partitions.
            continue
        result = query(conn, """SELECT CASE WHEN aggregation_basis='출발시간' THEN start_station_id
                 ELSE end_station_id END AS station_id,
                 CASE WHEN aggregation_basis='출발시간' THEN 'rentals' ELSE 'returns' END AS target,
                 FLOOR(TIME_TO_SEC(time_slot)/600) AS slot, SUM(ride_count) AS value
                 FROM station_flow_5m WHERE service_date=%s
                 AND (aggregation_basis='출발시간' OR (aggregation_basis='도착시간' AND end_station_id<>'X'))
                 GROUP BY station_id,target,slot""", (day,))
        result["value"] = result.value.astype(float)
        result["inferred_zero"] = False
        if zero_fill:
            # Station-day activity is only a proxy for operation throughout that day.
            index = pd.MultiIndex.from_product([result.station_id.unique(), ["rentals", "returns"], range(144)],
                                                names=["station_id", "target", "slot"])
            result = result.set_index(["station_id", "target", "slot"]).reindex(index)
            result["inferred_zero"] = result.value.isna()
            result["value"] = result.value.fillna(0)
            result = result.reset_index()
        result["timestamp"] = pd.Timestamp(day) + pd.to_timedelta(result.slot * 10, unit="m")
        path = output / f"od-{day}.csv.gz"
        result[["station_id", "timestamp", "target", "value", "inferred_zero"]].to_csv(
            path, index=False, compression={"method": "gzip", "compresslevel": 1, "mtime": 0})
        report["days"].append({"date": str(day), "rows": len(result),
                               "source_file": audit.iloc[0].source_file,
                               "source_sha256": audit.iloc[0].get("source_sha256"),
                               "inferred_zeros": int(result.inferred_zero.sum())})
        print(f"Exported {day}: {len(result):,} rows", flush=True)
    (output / "manifest.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


def inventory_labels(frame, horizon_minutes=15):
    """Exact time join: never shift across an outage, nor use a late collection as a current feature."""
    if horizon_minutes < 5 or horizon_minutes % 5:
        raise ValueError("horizon_minutes must be a positive multiple of five")
    data = frame.copy()
    data["observed_slot"] = pd.to_datetime(data.observed_slot)
    data["collected_at"] = pd.to_datetime(data.collected_at)
    if data.duplicated(["station_id", "observed_slot"]).any():
        raise ValueError("Duplicate inventory observations")
    valid = ((data.available_bikes >= 0) & (data.collected_at >= data.observed_slot)
             & (data.collected_at < data.observed_slot + pd.Timedelta(minutes=5)))
    data = data.loc[valid].copy()
    # Predict at the end of the observed slot, when that bucket's sample is available.
    data["timestamp"] = data.observed_slot + pd.Timedelta(minutes=5)
    # Label is the first 5-minute observation bucket starting horizon_minutes after decision time.
    data["label_slot"] = data.timestamp + pd.Timedelta(minutes=horizon_minutes)
    labels = data[["station_id", "observed_slot", "available_bikes", "collected_at"]].rename(
        columns={"observed_slot": "label_slot", "available_bikes": "value", "collected_at": "label_time"})
    result = data.merge(labels, on=["station_id", "label_slot"], how="inner", validate="one_to_one")
    result["target"] = "available_bikes"
    result["horizon_minutes"] = horizon_minutes
    return features(result)


def load_partitions(directory: Path, target: str, max_stations=200, max_rows=3_000_000):
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if max_stations < 1 or max_rows < 1:
        raise ValueError("Station and row limits must be positive")
    frames, selected, total = [], None, 0
    for item in manifest["days"]:
        frame = pd.read_csv(directory / f"od-{item['date']}.csv.gz")
        frame = frame.loc[frame.target.eq(target)]
        if selected is None:
            # Deterministic station cohort from first training day; no future-volume selection.
            selected = sorted(frame.station_id.unique())[:max_stations]
        frame = frame.loc[frame.station_id.isin(selected)]
        total += len(frame)
        if total > max_rows:
            raise ValueError("Training row limit exceeded; reduce date range or station cohort")
        frames.append(frame)
    if not frames or total == 0:
        raise ValueError("No valid OD observations; export data first")
    result = features(pd.concat(frames, ignore_index=True))
    if result.duplicated(["station_id", "timestamp", "target"]).any():
        raise ValueError("Duplicate training targets")
    if not np.isfinite(result.value).all() or (result.value < 0).any():
        raise ValueError("Invalid training targets")
    return result, manifest
