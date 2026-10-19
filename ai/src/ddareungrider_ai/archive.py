"""Download published OD archives by real catalog IDs, with resumable checksum records."""
import argparse
from datetime import date, datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import urllib.parse
import urllib.request
import zipfile

CATALOG = "https://data.seoul.go.kr/dataList/OA-21229/F/1/datasetView.do"
DOWNLOAD = "https://datafile.seoul.go.kr/bigfile/iot/inf/nio_download.do?useCache=false"


def discover(html):
    pattern = r'<span[^>]*title="(tpss_bcycl_od_statnhm_([0-9]{6}(?:[0-9]{2})?)\.zip)"[^>]*downloadFile\(\x27([0-9]+)\x27\)'
    return [{"filename": name, "period": period, "sequence": seq} for name, period, seq in re.findall(pattern, html)]


def choose(files, start, end):
    if start < date(2023, 3, 1) or start > end:
        raise ValueError("Current importer supports the post-2023-02 schema; choose start >= 2023-03-01 and end >= start")
    monthly = {item["period"]: item for item in files if len(item["period"]) == 6}
    chosen = {}
    for item in files:
        period = item["period"]
        day = datetime.strptime(period, "%Y%m" if len(period) == 6 else "%Y%m%d").date()
        if len(period) == 6:
            if start.replace(day=1) <= day <= end.replace(day=1):
                chosen[item["filename"]] = item
        elif start <= day <= end and period[:6] not in monthly:
            chosen[item["filename"]] = item
    return sorted(chosen.values(), key=lambda item: item["period"])


def checksum(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def fetch(item, directory, max_bytes=600_000_000):
    path = directory / item["filename"]
    receipt = path.with_suffix(".download.json")
    if path.exists() and receipt.exists():
        record = json.loads(receipt.read_text(encoding="utf-8"))
        if checksum(path) == record["sha256"]:
            print(f"Already verified: {path.name}", flush=True)
            return record
    if shutil.disk_usage(directory).free < 5_000_000_000:
        raise ValueError("Less than 5 GB disk space available")
    request = urllib.request.Request(DOWNLOAD, data=urllib.parse.urlencode(
        {"infId": "OA-21229", "infSeq": "1", "seqNo": "", "seq": item["sequence"]}).encode(),
        headers={"Referer": CATALOG})
    temp = path.with_suffix(".part")
    try:
        total = 0
        with urllib.request.urlopen(request, timeout=60) as response, temp.open("wb") as file:
            if not urllib.parse.urlparse(response.url).hostname.endswith(".seoul.go.kr"):
                raise ValueError("Unexpected download host")
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > max_bytes:
                    raise ValueError("Compressed archive size limit exceeded")
                file.write(chunk)
        with zipfile.ZipFile(temp) as archive:
            entries = archive.infolist()
            if not 1 <= len(entries) <= 366 or sum(entry.file_size for entry in entries) > 2_000_000_000:
                raise ValueError("Expanded archive size limit exceeded")
            names = [entry.filename for entry in entries]
            if len(set(names)) != len(names) or any(not re.fullmatch(r"tpss_bcycl_od_statnhm_[0-9]{8}\.csv", n) for n in names):
                raise ValueError("Unexpected or duplicate ZIP member")
            if archive.testzip() is not None:
                raise ValueError("Archive CRC failed")
        record = {**item, "sha256": checksum(temp), "compressed_bytes": total, "source": CATALOG}
        temp.replace(path)
        receipt.write_text(json.dumps(record, indent=2), encoding="utf-8")
        print(f"Downloaded and verified: {path.name} ({total:,} bytes)", flush=True)
        return record
    finally:
        temp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description="공식 월별 ZIP 및 최신 일별 OD 수집 (월 단위 파일은 전체 월을 포함)")
    parser.add_argument("--start", type=date.fromisoformat, required=True)
    parser.add_argument("--end", type=date.fromisoformat, required=True)
    parser.add_argument("--output", type=Path, default=Path("bikeseoul_data/data/oa-21229"))
    parser.add_argument("--catalog-only", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(CATALOG, timeout=45) as response:
        files = discover(response.read().decode("utf-8"))
    if not files:
        parser.error("Official file catalog format changed")
    selected = choose(files, args.start, args.end)
    (args.output / "official-catalog.json").write_text(json.dumps({"available": files, "selected": selected}, indent=2), encoding="utf-8")
    print(f"Catalog: {len(files)} files; selected {len(selected)} archives", flush=True)
    if not args.catalog_only:
        for item in selected:
            fetch(item, args.output)


if __name__ == "__main__":
    main()
