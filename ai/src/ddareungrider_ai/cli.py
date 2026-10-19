import argparse
from datetime import date
import json
from pathlib import Path

from .data import database, export_od, load_partitions, query, inventory_labels
from .model import train, predict


def main():
    parser = argparse.ArgumentParser(description="따릉라이더 오프라인 학습 도구 (한국 시간)")
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export-od")
    export.add_argument("--env", type=Path, default=Path(".env"))
    export.add_argument("--start", type=date.fromisoformat, required=True)
    export.add_argument("--end", type=date.fromisoformat, required=True)
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--zero-fill-active-days", action="store_true",
                        help="Experimental: treat absent bins as zero only within complete files and active station-days")
    fit = commands.add_parser("train")
    fit.add_argument("--data", type=Path, required=True)
    fit.add_argument("--output", type=Path, required=True)
    fit.add_argument("--target", choices=["rentals", "returns"], default="rentals")
    fit.add_argument("--stations", type=int, default=200)
    fit.add_argument("--max-rows", type=int, default=3_000_000)
    fit.add_argument("--half-life-days", type=float, default=0)
    fit.add_argument("--smoke-test", action="store_true")
    infer = commands.add_parser("predict")
    infer.add_argument("--model", type=Path, required=True)
    infer.add_argument("--station", required=True)
    for name in ["weekday", "hour", "minute", "month"]:
        infer.add_argument("--"+name, type=int, required=True)
    inventory = commands.add_parser("export-inventory")
    inventory.add_argument("--env", type=Path, default=Path(".env"))
    inventory.add_argument("--start", type=date.fromisoformat, required=True)
    inventory.add_argument("--end", type=date.fromisoformat, required=True)
    inventory.add_argument("--horizon", type=int, default=15)
    inventory.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command.startswith("export"):
            if args.end < args.start:
                raise ValueError("end must be on or after start")
            if args.output.exists():
                raise ValueError("Export destination must be new (prevents mixing stale partitions)")
            with database(args.env) as conn:
                if args.command == "export-od":
                    result = export_od(conn, args.start, args.end, args.output, args.zero_fill_active_days)
                else:
                    # Explicit bound: one export is limited to 7 days of all-station inventory.
                    if (args.end-args.start).days > 6:
                        raise ValueError("Inventory exports are limited to 7 days per file")
                    frame = query(conn, """SELECT station_id,observed_slot,available_bikes,collected_at
                        FROM station_inventory_5m WHERE observed_slot >= %s AND observed_slot < DATE_ADD(%s, INTERVAL 1 DAY)""",
                                  (args.start, args.end))
                    if frame.empty:
                        raise ValueError("No inventory observations in requested range")
                    labels = inventory_labels(frame, args.horizon)
                    args.output.parent.mkdir(parents=True, exist_ok=True)
                    labels.to_csv(args.output, index=False)
                    result = {"rows": len(labels), "target": "available_bikes", "note": "Labels only; separate future-availability model required"}
        elif args.command == "train":
            frame, manifest = load_partitions(args.data, args.target, args.stations, args.max_rows)
            result = train(frame, manifest, args.output, args.smoke_test, args.half_life_days)
            result = {k: result[k] for k in ["status", "selected_by_validation", "splits", "metrics"]}
        else:
            result = predict(args.model, args.station, args.weekday, args.hour, args.minute, args.month)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ValueError, FileNotFoundError) as exc:
        parser.exit(2, f"{exc}\n")
    except Exception as exc:
        # Driver errors can contain connection details. Never emit credentials or raw provider responses.
        parser.exit(1, f"Operation failed ({type(exc).__name__}); verify local DB and input data.\n")


if __name__ == "__main__":
    main()
