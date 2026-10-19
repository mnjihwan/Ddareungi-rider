# 따릉라이더 데이터 수집 — 2026-10-12

실제 길찾기에 필요한 대여소·잔여 수량을 우선하고, 도착 시 수량 예측을 위한 과거 이용량을 따로 저장합니다. Java 수집기는 데이터 수집·저장을 담당합니다. 별도 [Python AI 프로젝트](../ai/README.md)에 오프라인 수요 학습·평가 기반을 추가했습니다. 서비스 예측 서버 연결은 아직 없습니다.

2026-10-13부터 공식 월별 OD ZIP의 기간별 다운로드(`ddareungrider_ai.archive`)와 시간별 API 전 시간대 수집(`scripts/collect-hourly-range.ps1`)을 지원합니다. 일상 수집 스케줄과 별도로 실행하는 과거 자료 수집 도구입니다. 원본 포맷이 같은 2023-03 이후를 지원하며, 선택한 기간만 받습니다. 아래 2026-10-12 수치는 당시 검증 기록이며 최신 확대 결과는 `ai/README.md`와 `docs/ai-experiment-2026-10-13.md`를 참고하세요.

## 선택한 공식 데이터

| 데이터셋 | 제공 방식/현재 사용 서비스명 | 용도 및 저장 위치 |
| --- | --- | --- |
| [OA-15493 실시간 대여정보](https://data.seoul.go.kr/dataList/OA-15493/A/1/datasetView.do) | `bikeList` API | 잔여 자전거 수, 거치대 수, 위치 → `station_inventory_5m`, 기존 `station_history` |
| [OA-21235 대여소 마스터](https://data.seoul.go.kr/dataList/OA-21235/S/1/datasetView.do) | `bikeStationMaster` API | 대여소 ID·주소·좌표 → `station` |
| [OA-13252 대여소 정보](https://data.seoul.go.kr/dataList/OA-13252/F/1/datasetView.do) | `tbCycleStationInfo` API | 공식 대여소 번호와 `ST-...` ID 연결, 이름·자치구·거치대 정보 → `station` |
| [OA-21229 대여/반납 승객수](https://data.seoul.go.kr/dataList/OA-21229/F/1/datasetView.do) | ZIP 안의 5분 단위 OD CSV | 출발·도착 기준 이용량 → `station_flow_5m` |
| [OA-15245 시간대별 이용정보](https://data.seoul.go.kr/dataList/OA-15245/F/1/datasetView.do) | `tbCycleRentUseTimeInfo` API / CSV | 대여소별 시간대 이용 패턴 → `station_usage`, `resolution=hour` |
| [OA-15246 일별 이용정보](https://data.seoul.go.kr/dataList/OA-15246/F/1/datasetView.do) | `tbCycleRentUseDayInfo` API / CSV | 일별 수요 및 요일 패턴 → `station_usage`, `resolution=day` |
| [OA-15248 월별 이용정보](https://data.seoul.go.kr/dataList/OA-15248/F/1/datasetView.do) | `tbCycleRentUseMonthInfo` API / CSV | 장기 계절성·이용량 → `station_usage`, `resolution=month` |
| [OA-15249 대여소별 월별 이용정보](https://data.seoul.go.kr/dataList/OA-15249/F/1/datasetView.do) | `tbCycleStationUseMonthInfo` API | 대여소 월별 총 이용량 → `station_usage`, 별도 `dataset_id=OA-15249` |

위 추가 API 5종은 현재 `.env`의 `SEOUL_API_KEY`로 실제 정상 응답을 확인했습니다. 호출 서비스명과 JSON 루트명이 다릅니다. 예를 들어 `tbCycleStationInfo`의 루트는 `stationInfo`, 시간별 통계는 `cycleRentUseTimeInfo`입니다. 파일 목록과 API의 자료 공개 시점은 같다고 가정하지 않으며, 날짜 요청마다 실제 응답을 확인합니다.

OD는 공식 안내상 **매일 5일 전 데이터**를 갱신하는 파일 자료입니다. 실시간 재고를 대신하지 않습니다. 인구·도로·기상 정보가 포함된 [OA-21285 실시간 도시데이터](https://data.seoul.go.kr/dataList/OA-21285/A/1/datasetView.do)도 확인했지만 지정 장소 단위의 자료이므로, 전체 대여소와 연결하는 공간 기준을 정한 후 추가하는 후보로 남겼습니다. 원시 개별 대여이력·가입자 통계·중복 영문 데이터는 현재 길찾기의 핵심 데이터 수집 대상에 포함하지 않았습니다.

## 자동 실행

수집기와 MySQL이 실행되는 동안 한국 시간으로 동작합니다.

| 시점 | 동작 |
| --- | --- |
| 시작 직후 | 기존 마스터·현재 수량 수집 |
| 시작 후 약 15초 | 대여소 상세, OD 파일 확인·다운로드, 보유 CSV 및 최근 이용 통계 적재 |
| 매 5분 정각 | 현재 수량을 `station_inventory_5m`에 저장 |
| 매일 03:20 | 기존 마스터 동기화 |
| 매일 04:10 | 상세 정보, OD 파일, 보유 CSV, 통계 API 수집 |

긴 파일·통계 수집이 5분 수량 수집을 막지 않도록 스케줄러 풀을 2개로 구성했고 대여소 DB 쓰기의 잠금 순서를 정렬했습니다. 기존 시간별 `station_history`는 같은 시간의 마지막 수집 값으로 갱신하고 새 5분 이력과 구분합니다. 서버·PC가 꺼지거나 절전 상태이면 실시간 수량은 수집되지 않으며 과거 재고를 복원하지 않습니다.

OD 다운로드는 공식 페이지에 실제 게시된 **일별 파일과 다운로드 식별자**를 읽어 POST로 내려받습니다. 최근 14일 범위, 실행당 최대 7개 파일로 제한하며 이미 있는 파일을 건너뜁니다. 가장 최근 게시 파일은 다시 받아 수정 여부를 체크섬으로 확인합니다. 월별 대형 ZIP은 자동 대량 다운로드하지 않으며 보유 월별 ZIP을 입력 폴더에 넣으면 내부 일별 CSV를 읽을 수 있습니다. 파일 크기·압축 해제 크기·허용 파일명·ZIP 경로를 검증합니다. 공식 페이지 형식이 바뀌면 오류로 알리고 링크나 날짜를 추측하지 않습니다.

통계의 기본 대상은 `station-month,day,hour`입니다. 일별·시간별은 현재 날짜에서 `USAGE_API_LAG_DAYS`(기본 5일)를 뺀 날짜의 자료를 요청하고, 시간별은 0~23시를 각각 호출합니다. 월별은 직전 2개 월을 요청합니다. 미공개 날짜는 `EMPTY`로 기록하고 기존 자료를 삭제하지 않습니다. API는 1,000행씩 전 페이지를 읽고 최대 200페이지까지 처리하며, 마지막까지 읽지 못하면 부분 합계를 저장하지 않습니다.

## 파일 입력과 단발 실행

- OD CSV/ZIP: `bikeseoul_data/data/oa-21229/`
- 월별·일별·시간별 이용통계 CSV: `bikeseoul_data/data/usage/`
- CSV 인코딩: MS949 및 UTF-8(BOM 포함). 공식 헤더로 구분하며 잘못된 본문 문자는 대체하지 않고 적재를 중단합니다. 실제 2026-09 월별 ZIP의 09-18 파일은 UTF-8 **헤더만 있는 199바이트 빈 파일**이었습니다. `collection_run`에 `EMPTY`로 기록하고 학습에서 제외하며, 기존 정상 데이터가 있다면 보존합니다.

통계 CSV는 `대여일자,대여소번호,대여소명,이용건수,이용시간(분),이동거리(M)` 헤더를 사용하며 시간별 파일은 `대여시간`도 사용합니다. 다른 헤더의 파일을 같은 자료로 임의 변환하지 않습니다.

프로젝트 최상위에서 실행합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\collect-data-once.ps1
```

이 스크립트는 Downloads의 해당 따릉이 CSV/ZIP을 입력 폴더로 복사하고 새 OD 최대 2개와 직전 월·6일 전 일별 통계·그날 **0~23시 전체** 통계를 수집합니다(2026-10-13 기본값 확대). 기본 날짜는 한국 시간으로 계산합니다. 웹·백엔드는 시작하지 않으며, 처음에 꺼져 있던 DB와 Docker는 작업 후 다시 종료합니다. 원본 Downloads 파일과 DB 볼륨을 삭제하지 않습니다. 현재 키를 환경변수로만 전달하고 API 키를 명령 인자나 로그에 출력하지 않습니다.

특정 기간을 다시 가져올 수도 있습니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\collect-data-once.ps1 -SkipDownload -UsageTasks "station-month:2026-09,day:2026-10-06,hour:2026-10-06:9"
```

`month:2026-06`은 상세 월별 API를 조회합니다. 통계의 과거 기간은 이렇게 명시해 재수집하며, 전체 과거 연도를 무제한으로 자동 다운로드하지 않습니다. 서버가 꺼져 있던 기간의 통계·파일도 해당 기간을 지정하거나 공식 월별 ZIP을 입력 폴더에 넣어 보완할 수 있습니다. `FLOW_CSV_IMPORT_ENABLED`는 이전 초안의 설정으로 현재 추가 파이프라인의 스위치가 아닙니다. 추가 수집을 끄려면 `EXTENDED_COLLECTION_ENABLED=false`, 다운로드만 끄려면 `OD_DOWNLOAD_ENABLED=false`를 사용합니다.

## 정합성과 해석

- OD는 파일의 SHA-256이 같으면 DB 재적재를 건너뜁니다. 변경 파일은 해당 파일의 기존 행 삭제·새 행 적재·완료 기록을 하나의 트랜잭션으로 처리합니다. 뒤쪽 행에서 실패해도 이전 정상 파일의 DB 상태가 유지됩니다.
- 통계 CSV는 같은 체크섬의 현재 적재를 건너뜁니다. 통계는 대여소·기간 기준으로 합산하고 재수집 시 대체하며 누적 덧셈하지 않습니다. 저장값과 완료 체크섬은 같은 트랜잭션에 기록합니다.
- 이용 시간·거리·거치대 수가 원본에서 비어 있으면 `NULL`로 보존합니다. OD의 목적지 `X`는 원문 그대로 저장하며 실제 `ST-...` 대여소로 간주하지 않습니다.
- OD의 **출발시간/도착시간 집계는 같은 이용을 서로 다른 기준으로 세는 자료**이므로 양쪽 건수를 합해 총 이용 건수로 해석하지 않습니다. CSV의 행 수 또한 실제 이용 건수와 다릅니다.
- 성별·연령별 원본 행은 대여소별 총량으로 집계하고 성별·연령 필드는 저장하지 않습니다. 이용시간과 거리는 각각 분·미터로 저장합니다.
- 통계의 대여소 번호와 실시간의 `ST-...` ID를 숫자만 보고 같다고 가정하지 않습니다. 공식 상세 API의 번호를 사용하고, `station_usage_with_id` 뷰는 번호가 하나의 현재 ID에 대응할 때만 연결합니다. 미매핑·모호한 번호는 `NULL`입니다. 과거 폐쇄·재설치 시점의 매핑까지 보장하지는 않습니다.
- 월별 두 자료(`OA-15248`, `OA-15249`)는 각각 저장하며 합산하지 않습니다. 같은 자료의 CSV·API는 같은 기간·대여소 키를 대체하고 `source_file`에 마지막 출처를 기록합니다.
- `collection_run`은 `COMPLETED/EMPTY/FAILED`, 원본·저장 행 수, 마지막 시도, 오류 종류를 기록합니다. 인증키·원천 오류 본문은 저장하지 않습니다.

## 2026-10-12 실제 검증 결과

- 기존 MySQL에 Flyway V2/V3 추가 마이그레이션을 적용했고 원래 데이터·테이블을 삭제하지 않았습니다.
- 공식 대여소 상세 3,245개를 저장하고 번호와 ID를 연결했습니다.
- 2024년 7~12월 CSV 619,664행을 대여소·월 기준 16,401행으로 집계했습니다.
- 2026-08-24, 09-03, 10-04, 10-05, 10-06 OD **1,293,099행**, 날짜마다 288개 5분 시간대를 저장했습니다. 전체 원본 행 수와 DB 행 수가 일치했고 목적지 `X` 및 40,269행의 빈 시간·거리 값을 보존했습니다.
- 2026-09 대여소별 월 통계 2,762행, 2026-10-06 일 통계 2,743행, 같은 날 09시 시간 통계 2,024행을 저장했습니다. 원본은 각각 2,762 / 73,083 / 6,371행입니다. 시간별 검증 자료는 하루 전체 24시간이 아닙니다.
- 5분 수량 이력을 실제 API 응답으로 저장했습니다. 과거 시간별 이력을 5분 자료로 보간해 채우지 않았습니다.
- 수집기 테스트 23개 및 빌드를 통과했습니다. 페이지 누락·빈 응답·잘못된 기간·키 노출 방지·중복·ZIP 경로 및 크기·DB 롤백·원본 빈 값과 `X`를 검증합니다. 실제 재실행에서도 OD 행 수·통계 합계가 증가하지 않고 같은 CSV를 건너뛰는 것을 확인했습니다.

상태 조회 SQL은 `scripts/collector-status.sql`, 단발 실행 로그와 상태표는 `.local/collection-once.log`, `.local/collection-status.tsv`입니다. 서버가 중지 상태였으므로 검증 후 원래의 중지 상태로 복구했습니다.
