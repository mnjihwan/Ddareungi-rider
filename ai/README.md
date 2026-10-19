# 따릉라이더 Python 학습 기반

첨부된 `crowding_ai_discussion_summary.md`의 **요일·시간대 통계 → LightGBM 비교 → 시간순 검증**을 따릉이 대여소에 적용했습니다. 초안의 5년치·3,000개 장소는 보유 데이터의 사실로 사용하지 않습니다. 사람 혼잡도 대신 **대여·반납 이용 건수/10분**을 각각 학습합니다. 길찾기를 지원하는 부가 기능의 오프라인 실험 코드입니다.

첫 실제 36일·200개 대여소 실험은 완료했습니다. 수집 범위·누락 날짜·성능 비교는 [2026-10-13 결과](../docs/ai-experiment-2026-10-13.md), 완료 모델은 `ai/artifacts/20261013-validated/{rentals,returns}`에 있습니다. 이번 설정에서는 두 대상 모두 전체 검증 MAE가 더 낮은 통계 방식이 선택됐습니다.

## 설치

프로젝트 최상위에서 Python 3.11 이상으로 실행합니다. 현재 PC의 Python 3.14에서 검증합니다. GPU는 필요하지 않고 학습 스레드는 4개로 제한합니다.

```powershell
python -m venv .venv-ai
.\.venv-ai\Scripts\python.exe -m pip install -r ai/requirements-lock.txt
.\.venv-ai\Scripts\python.exe -m pip install -e './ai[test]'
.\.venv-ai\Scripts\python.exe -m pytest ai/tests -q
```

`requirements-lock.txt`는 Windows/Python 3.14에서 실제 설치한 버전입니다. 다른 Python 버전에서는 호환되는 의존성을 설치하도록 `pip install -e './ai[test]'`를 사용합니다. 키는 기존 최상위 `.env`를 읽으며 코드·모델에 기록하지 않습니다.

## 자료 확대

[OA-21229](https://data.seoul.go.kr/dataList/OA-21229/F/1/datasetView.do)의 월별 ZIP은 한 달 전체의 **5분 단위 원본**을 포함합니다. 월별 합계 데이터가 아닙니다. 공식 페이지에서 실제 다운로드 번호를 발견해 가져오고, SHA-256·ZIP 파일명·CRC·용량을 검사합니다. 같은 파일의 재실행은 다운로드를 건너뜁니다. 공급자가 파일을 수정했다면 해당 `.download.json` 확인 기록을 제거하여 다시 받을 수 있습니다.

```powershell
# 목록과 선택 범위만 확인 (원본을 다운로드하지 않음)
.\.venv-ai\Scripts\python.exe -m ddareungrider_ai.archive --start 2023-03-01 --end 2026-10-07 --catalog-only
# 실제 실행: 월 파일이 있으면 일 파일과 중복 다운로드하지 않음
.\.venv-ai\Scripts\python.exe -m ddareungrider_ai.archive --start 2026-09-01 --end 2026-10-07
# 기존 수집기가 ZIP 해제·일별 트랜잭션 적재·완료 체크섬 기록
.\scripts\collect-data-once.ps1 -SkipDownload
# 시간별 API 하루 0~23시 전체, 원하는 날짜 범위로 반복 가능
.\scripts\collect-hourly-range.ps1 -StartDate 2026-10-01 -EndDate 2026-10-07 -IncludeDaily
```

공식 목록에는 더 오래된 파일도 있으나, 2023-02-26 이전에는 집계 기준 등의 컬럼이 다릅니다. 현재 가져오기 계약은 2023-03 이후이며 이전 자료를 현재 대여·반납 라벨로 추측하지 않습니다. 월별 압축 파일은 요청한 날짜가 그 달 일부여도 전체 월을 다운로드합니다. 오래된 기간은 작은 범위부터 검증하세요. 수년치의 다운로드·DB 저장 용량과 처리 시간은 별도입니다. 이번 실행에서 수년치 전체를 적재했다고 간주하면 안 됩니다.

시간별·일별·월별 자료는 기존 `station_usage`에 개별 저장합니다. 10분 학습에는 5분 OD만 사용합니다. **시간별 값을 6등분해 가짜 10분 정답으로 만들거나 서로 다른 해상도의 통계 건수를 더하지 않습니다.** 시간별 자료는 추후 시간 단위 수요 모델과 수집 품질 점검에 사용할 수 있습니다.

## 실제 DB로 실행

```powershell
.\scripts\train-ai.ps1 -StartDate 2026-09-01 -EndDate 2026-10-07 -Stations 200 -ZeroFillActiveDays
```

필요한 MySQL만 잠시 시작하고 종료 상태를 복구합니다. 결과는 `ai/data/<실행시각>`과 `ai/artifacts/<실행시각>/{rentals,returns}`에 저장합니다. 웹 서버는 시작하지 않습니다. 스크립트는 원본 수집을 수행하지 않으므로 먼저 DB에 적재해야 합니다.

분리해서 실행할 수도 있습니다(MySQL 실행 필요).

```powershell
.\.venv-ai\Scripts\python.exe -m ddareungrider_ai.cli export-od --start 2026-09-01 --end 2026-10-07 --output ai/data/experiment-1 --zero-fill-active-days
.\.venv-ai\Scripts\python.exe -m ddareungrider_ai.cli train --data ai/data/experiment-1 --output ai/artifacts/experiment-1/rentals --target rentals --stations 200
.\.venv-ai\Scripts\python.exe -m ddareungrider_ai.cli train --data ai/data/experiment-1 --output ai/artifacts/experiment-1/returns --target returns --stations 200
.\.venv-ai\Scripts\python.exe -m ddareungrider_ai.cli predict --model ai/artifacts/experiment-1/rentals --station ST-10 --weekday 1 --hour 18 --minute 20 --month 10
```

요일은 월요일 0~일요일 6, 분은 0/10/20/30/40/50입니다. 날짜 없는 조회에도 계절 입력을 명확히 하기 위해 월은 필수입니다. 미래 날짜 날씨나 현재 재고를 평소 패턴 모델 입력으로 요구하지 않습니다. 신규 대여소는 전체 통계로 내려가며 `known_station=false`와 보완 수준을 반환합니다. 이는 그 장소를 학습했다는 뜻이 아닙니다.

## 정답·결측 처리

- 출발시간 기준 → 시작 대여소의 `rentals`, 도착시간 기준 → 종료 대여소의 `returns`. 같은 이용을 두 번 더하지 않습니다. 반납 장소 `X`는 대여 수요에는 포함될 수 있으나 반납 대여소 라벨에서 제외합니다.
- SQL에서 날짜·대여소·10분 단위로 먼저 합산하고 날짜별 파일을 작성합니다. 대여 건수는 구간 **합계**이며, 재고 수량의 구간 평균과 다릅니다.
- 성공한 원본 적재 기록의 행 수와 실제 DB 행 수, 하루 전체 288개 시간대, 두 집계 기준을 확인한 날짜만 내보냅니다. 누락된 날은 `manifest.json`에 따로 기록합니다.
- 기본값은 관측된 구간만 유지합니다. 이 결과는 **활동이 기록된 구간에 조건부인 수요**이므로 하루 전체 수요처럼 사용하면 안 됩니다.
- `--zero-fill-active-days`는 완성된 원본 파일에서 해당 날짜에 활동이 확인된 대여소의 빈 구간을 0으로 채우는 **명시적인 실험 가정**입니다. `inferred_zero`로 구분합니다. 그날 종일 운영했는지·일시 폐쇄됐는지는 보장하지 않습니다. 하루 전체 미등장 대여소나 누락된 날은 0으로 만들지 않습니다. 서비스 배포 전 운영 기간·누락 정책 검증이 필요합니다.
- 학습은 첫 관측일에 있는 대여소 ID를 정렬하여 최대 200개를 선택합니다. 테스트 기간 이용량으로 인기 대여소를 고르지 않습니다. 기본 300만 행 제한을 넘으면 기간·대여소 수를 줄이도록 안내합니다. 전체 3,000개 확장 시 파티션 학습/메모리 계획이 필요합니다.

## 학습·평가 계약

기준 모델은 대여소×요일×10분 구간의 중앙값·25/75분위수·관측 일수입니다. 같은 구간이 최소 3일 없으면 대여소×요일×시간 → 대여소 → 전체 통계 순서로 보완합니다. 분위수는 **과거 관측의 가운데 50% 범위**이며 예측 신뢰구간이 아닙니다.

공통 LightGBM은 대여소 범주·요일·10분 구간·월을 받아 Poisson 목적함수로 이용 건수를 학습합니다. [공식 Python 학습·모델 저장 문서](https://lightgbm.readthedocs.io/en/stable/Python-Intro.html). `--half-life-days 180` 같은 최근 가중치를 실험할 수 있지만 기본값은 가중치 없음입니다. 기간/가중치 선택에도 검증 구간만 사용해야 합니다.

최소 학습 14개 관측일, 뒤 7개 관측일 검증, 마지막 7개 관측일 테스트로 나눕니다. **관측일 기준**이므로 날짜가 빠지면 달력상 28일보다 길어집니다. 통계와 범주 사전은 학습 자료로만 만들고, 조기 종료·모델 선택은 검증 MAE로 결정합니다. 테스트는 선택 후 비교 보고만 합니다. MAE·대여소별 평균 MAE·요일/월별 MAE·학습에서 정한 상위 수요 임계값의 MAE를 저장합니다. 한 달짜리 결과로 계절성이나 연간 성능을 검증했다고 해석하면 안 됩니다.

`--smoke-test`는 자료가 적을 때만 실행 경로 확인용입니다(최소 3개 관측일). 결과에 `smoke_test_only`를 표시합니다. 일반 학습도 `offline_experiment`, `production_ready=false`를 유지합니다. 성능이 좋다는 이유만으로 웹 예측에 자동 연결하지 않습니다.

결과 파일:

- `baseline.json`: 학습 구간의 통계와 보완 기준
- `lightgbm.txt`: 저장한 모델
- `source-manifest.json`: 원본 파일명·SHA-256·날짜별 전처리 행 수와 결측 처리 가정
- `report.json`: 대상, 단위, 범주 사전, 날짜별 분할, 평가, 결측 가정, 라이브러리 버전
- `test-predictions.csv.gz`: 미사용 테스트 구간의 정답과 두 모델의 예측
- `station-metrics.csv`: 대여소별 테스트 MAE와 관측 수. `report.json`에는 분할별 0의 비중·추정 0의 비중·제외 날짜도 포함합니다.

## 미래 잔여 수량 학습의 출발점

`export-inventory`는 `station_inventory_5m`에서 재고 학습용 정답을 생성합니다. 대여·반납 수요를 잔여 자전거로 바꾸는 모델은 아직 아닙니다.

```powershell
.\.venv-ai\Scripts\python.exe -m ddareungrider_ai.cli export-inventory --start 2026-10-12 --end 2026-10-13 --horizon 15 --output ai/data/inventory-15min.csv
```

현재 5분 버킷이 끝난 시점을 판단 시각으로 두고, 15분 뒤 시작하는 **5분 관측 버킷**의 실제 수량을 정답으로 연결합니다. 지연 수집·음수·중복은 배제하고 정확한 시간 버킷이 없으면 해당 샘플을 버립니다. 행 순서를 밀어 수집 장애를 건너뛰지 않습니다. 미래 재고 모델을 학습할 때 분할 경계를 넘는 `label_time`은 `chronological_split`이 제거하도록 준비했습니다. 해당 전용 모델·고갈 확률 보정·서비스 API는 후속 범위입니다.

거치대 수는 반납 가능 자리의 엄격한 최대 용량으로 사용하지 않습니다. 잔여 재고는 재배치에도 영향을 받으므로 과거 OD만으로 재구성하지 않습니다. 공휴일·기상·행사 및 이전/폐쇄 이력은 아직 학습 입력에 포함되지 않습니다.

## Git에 포함되는 범위

소스·테스트·의존성·실행 안내는 통합 저장소에 포함합니다. `data/`, `artifacts/`, 가상환경과 원본 DB는 포함하지 않습니다. 위 모델 경로와 실험 결과는 로컬 실행 기록이며, 새로 복제한 환경에서는 데이터를 수집하고 학습해 모델을 생성해야 합니다. 평가 요약과 데이터 범위는 `docs/ai-experiment-2026-10-13.md`로 공유합니다.
