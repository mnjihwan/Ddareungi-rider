# 따릉이 서비스 Backend API 명세서 — Production 기준 (v5 검토본)

> **Backend**: Java 21 / Spring Boot 4.1.0  
> **Client**: Web (PC / 모바일 브라우저)  

> 2026-10-06 범위 변경: 네이티브 앱은 제외합니다. 아래 앱 버전·강제 업데이트·스토어 URL·네이티브 SDK 관련 항목은 이전 설계이며 현재 구현 대상이 아닙니다. 로컬 실행과 현재 구현 범위는 최상위 README를 기준으로 합니다.
> **API Version**: `v1`  
> **Base Path**: `/api/v1`  
> **CI/CD**: GitHub Actions + Docker + AWS ECR + EC2  
> **목표**: 실제 운영 환경 기준의 API Contract 정의

---

> **문서 개정일**: 2026-09-16. 이 문서는 구현이 따라야 할 설계 기준이며, 현재 코드의 구현 현황을 설명하지 않는다. v4의 아키텍처·엔드포인트·원천 필드명 유지 원칙을 보존했다.
>
> **검토 표기**: 명백한 오류 수정과 누락된 계약 보완을 구분한다. `제안 기본값`은 원문에 없던 설계 제안이며, 서비스 정책이나 검증된 모델 성능으로 확정된 값이 아니다. 41장의 미확정 항목은 운영 공개 전에 결정해야 한다.
>
> 모든 예시의 대여소 ID·이름·수량·경로는 설명용 데이터이며 실제 대여소와 경로의 일치를 보장하지 않는다.

> **사용자 결정 반영**: 폐쇄·불완전 대여소 조회 제외, 유효한 FRESH·0대는 정상 수량, Redis 우선/MySQL fallback, 출처 메타데이터 필수 보존, 검증 완료 매핑만 채택. 아래 상세 규칙은 같은 날짜의 초기 v5 검토안을 대체한다. 최신성 시간 임계값 자체는 여전히 제안값이다.

# 1. 문서 목적

본 문서는 서울시 따릉이 데이터를 활용한 웹·모바일 서비스의 **실제 운영 환경 기준 Backend API 명세**를 정의한다.

서비스의 기본 사용자 경험은 다음과 같다.

1. 앱 또는 웹 실행
2. 현재 위치 획득
3. 현재 위치 주변 대여소 자동 조회
4. 대여소별 실시간 대여 가능 자전거 수 표시
5. 사용자가 직접 장소를 검색할 경우 해당 위치 주변 대여소 조회
6. 대여소 상세 및 미래 자전거 수 예측
7. 목적지를 지정하면 도보 + 자전거 복합 경로 계산
8. 실시간 데이터 장애 또는 지연 발생 시 사용자에게 상태 안내

기본 기능은 **비회원 상태에서도 사용 가능**하도록 한다.

회원, 운동 기록, 개인화 추천 등은 별도 도메인으로 확장할 수 있지만 이 문서에서는 핵심 서비스 범위에 집중하며 미확정 운영 정책은 41장에서 구분한다.

---

# 2. 전체 시스템 구성

```text
┌──────────────────────┐
│ Web / React Native   │
└──────────┬───────────┘
           │ HTTPS
           ▼
┌─────────────────────────────┐
│ Spring Boot Main Service    │
│                             │
│ Station / Search / Route    │
│ Prediction / ClientConfig   │
└──────┬────────┬─────────────┘
       │        │
       │        ├───────────────────────────────────┐
       │        │                                   │
       ▼        ▼                                   ▼
┌──────────┐ ┌───────────────┐               ┌───────────────┐
│  MySQL   │ │ Elasticsearch │               │     Redis     │
│          │ │               │               │               │
│ 기준정보 │ │ Geo Search    │               │ 실시간 상태   │
│ 이력데이터│ │ 대여소 검색   │               │ Cache         │
└──────────┘ └───────────────┘               └───────────────┘
       │
       ├──────────────┐
       ▼              ▼
PlaceSearchProvider   Route Providers
                     ├── WalkingRouteProvider
                     └── BicycleRouteProvider

                       │
                       ▼
                AI Prediction Service
```

AI 학습용 과거 데이터와 실시간 원천 데이터 수집은 사용자 API와 분리한다.

```text
서울시 따릉이 API
       │
       ▼
Data Collector
       │
       ├── MySQL History + Latest Snapshot + Source Metadata
       └── Redis Realtime
```

---

# 3. 서울시 공공데이터 원천 기준

서비스의 따릉이 관련 데이터 모델은 서울 열린데이터광장에서 제공하는 원천 데이터의 필드명을 우선 기준으로 한다.

서비스 API에서는 원천 필드명을 가능한 한 유지하되 다음과 같은 서비스 계약 필드를 별도로 추가한다.

```text
거리 계산값
데이터 최신성 상태
AI 예측값
Route 계산 결과
Request 추적용 Metadata
```

서울시 Open API가 숫자값을 문자열로 반환하는 경우에도 서비스 API에서는 필드명은 유지하고 타입만 Number로 정규화할 수 있다.

## 3.1 사용 데이터셋

| Dataset ID | 데이터셋 | 형태 | 서비스 사용 목적 |
|---|---|---|---|
| `OA-13252` | 서울시 공공자전거 따릉이 대여소 정보 | File | 대여소 기준정보 보강 |
| `OA-15493` | 서울시 공공자전거 따릉이 실시간 대여정보 | Open API | 현재 대여소 상태 |
| `OA-20447` | 서울시 공공자전거 따릉이 대여소 운영현황(시간대별) | Open API | 최근 시간대별 상태 수집 |
| `OA-22382` | 서울시 대여소별 공공자전거 따릉이 대여가능 수량 | File | 장기 시간대별 대여가능 수량 학습 데이터 |
| `OA-21229` | 서울시 공공자전거 따릉이 대여소별 대여/반납 승객수 정보 | File | 대여·반납 흐름 및 AI Feature 보강 |

대여소 ID 기반 결합이 필요한 경우 서울시 공공자전거 따릉이 대여소 마스터 정보의 `ST-*` 계열 대여소 ID를 기준 식별자로 사용할 수 있다.

---

## 3.2 실시간 대여정보 원천 API

서울 열린데이터광장 서비스명:

```text
bikeList
```

원천 호출 형식:

```text
http://openapi.seoul.go.kr:8088/{KEY}/json/bikeList/{START_INDEX}/{END_INDEX}/
```

원천 API는 한 번에 최대 1,000건 단위로 조회하므로 전체 대여소 갱신 시 Paging하여 수집한다.

### 원천 Request

| 변수명 | Type | 필수 | 설명 |
|---|---|:---:|---|
| `KEY` | String | O | 서울 열린데이터광장 인증키 |
| `TYPE` | String | O | `json` |
| `SERVICE` | String | O | `bikeList` |
| `START_INDEX` | Integer | O | 요청 시작 위치 |
| `END_INDEX` | Integer | O | 요청 종료 위치 |
| `stationId` | String | 확인 필요 | 선택 검색 인자 지원 여부와 위치는 실제 원천 API 명세/인증 호출로 확인 후 사용 |

`START_INDEX`는 1부터 시작하며 `END_INDEX`는 포함한다. 한 요청의 범위는 최대 1,000건이다. 예시의 2회 호출을 전체 수집 횟수로 고정하지 않는다. 응답의 실제 행 수·종료 응답을 확인하여 끝까지 수집하고, 안전 상한에 도달하면 전체 성공으로 처리하지 않는다. `list_total_count`의 의미도 실제 응답으로 확인한다.

HTTP 200만으로 원천 호출 성공을 판단하지 않는다. `RESULT.CODE`, 응답 구조, 행별 필수값을 검증하고 오류 응답이나 파싱 실패를 자전거 0대로 변환하지 않는다. 인증키가 URL 경로에 포함되므로 원천 요청 URL은 로그에서 마스킹한다.

### 원천 Response Row

| 원천 필드 | 의미 | 서비스 내부 기준 |
|---|---|---|
| `rackTotCnt` | 거치대개수 | 동일 필드명 유지 |
| `parkingBikeTotCnt` | 자전거주차총건수 | 동일 필드명 유지 |
| `shared` | 거치율 | 동일 필드명 유지 |
| `stationLatitude` | 위도 | 동일 필드명 유지 |
| `stationLongitude` | 경도 | 동일 필드명 유지 |
| `stationId` | 대여소ID | 동일 필드명 유지 |
| `stationName` | 대여소이름 | 동일 필드명 유지 |

공통 응답:

```text
list_total_count
RESULT.CODE
RESULT.MESSAGE
```

서비스 내부에서는 원천 의미가 같은 값을 `availableBikeCount`, `rackTotalCount`, `sharedRate` 등으로 중복 정의하지 않고 `parkingBikeTotCnt`, `rackTotCnt`, `shared`를 기본 필드명으로 사용한다.

명시적인 반납 가능 슬롯 수는 원천 API가 제공하지 않으므로 기본 API Contract에 포함하지 않는다.

---

## 3.3 시간대별 운영현황

최근 시간대별 상태 데이터는 다음 원천 필드를 기준으로 적재한다.

```text
rackTotCnt
stationName
parkingBikeTotCnt
shared
stationLatitude
stationLongitude
stationId
stationDt
```

`stationDt`는 원천 값 보존 컬럼과 조회/학습 편의를 위한 DateTime 변환값을 함께 관리할 수 있다.

```text
원천 stationDt: 2026091620
변환 stationDateTime: 2026-09-16T20:00:00+09:00
```

최근 7일 Open API 데이터는 Incremental 수집 및 누락 보정에 사용한다.

---

## 3.4 장기 대여가능 수량 데이터

`OA-22382`는 대여소별 대여가능 수량을 1시간 단위의 파일 데이터로 제공한다.

AI 학습용 장기 이력을 초기 적재하는 데 사용하고, 이후 최근 데이터는 시간대별 운영현황 API를 통해 보완한다.

분기별 원본 파일은 Raw 영역에 보존하고 Import Adapter에서 원본 Header를 검증한 뒤 Canonical Field로 Mapping한다.

공통적으로 다음 개념을 유지한다.

```text
stationId
stationDt
parkingBikeTotCnt
```

원천에 추가로 존재하는 필드는 삭제하지 않고 Raw Import 기록 또는 확장 컬럼으로 보존한다.

---

## 3.5 대여/반납 승객수 데이터

`OA-21229`는 대여·반납 흐름 분석용 후보 데이터다. 원문에 기재된 5분 집계 단위와 아래 컬럼은 실제 파일로 확인해야 한다. 현재 대여가능 수량 Snapshot과 동일하게 취급하지 않는다.

원본 파일 검증 대기 컬럼:

```text
기준_날짜
집계_기준
기준_시간대
시작_대여소_ID
시작_대여소명
종료_대여소_ID
종료_대여소명
전체_건수
전체_이용_분
전체_이용_거리
```

해당 데이터는 `parkingBikeTotCnt`의 직접 대체값으로 사용하지 않는다.

AI Feature 및 분석 데이터 예:

```text
대여소별 유입량
대여소별 유출량
시간대별 대여 수요
시간대별 반납 수요
평균 이용시간
평균 이동거리
```

---

## 3.6 대여소 기준정보

`OA-13252`는 대여소 이름, 관리번호, 위치정보, 거치대 수 등 대여소 기준정보를 제공한다.

파일 Import 시 원본 Header를 보존하고 실시간 API와 겹치는 값은 다음 Canonical Field에 Mapping한다.

```text
stationName
stationLatitude
stationLongitude
rackTotCnt
```

서로 다른 원천 데이터에서 대여소 식별자가 다를 경우 `stationId`와 원본 관리번호를 별도 컬럼으로 보존하고 Mapping Table로 연결한다.

---

### 원천 데이터 확인 범위

공식 안내에서 실시간 데이터의 1회 최대 1,000건, 시간대별 운영현황의 최근 7일 제공, 장기 파일의 1시간 단위를 확인했다. 동적 API 표와 파일 원본을 전부 검증한 것은 아니므로 `stationDt`의 실제 형식, 선택 검색 인자, 파일 헤더·단위는 샘플 응답/파일로 추가 검증한다. 특히 `OA-21229` 페이지 본문을 이번 검토에서 확보하지 못했으므로 3.5의 5분 단위 및 컬럼 목록은 검증 대기인 매핑 후보로 취급한다.

서로 다른 데이터셋의 대여소 번호를 문자열 접두사만 붙여 `ST-*` ID로 변환하지 않는다. 마스터와 검증된 매핑으로 결합하고, 매핑 실패·폐쇄·이전 대여소는 별도 관리한다.

근거: [실시간 대여정보](https://data.seoul.go.kr/dataList/OA-15493/A/1/datasetView.do), [시간대별 운영현황](https://data.seoul.go.kr/dataList/OA-20447/A/1/datasetView.do), [장기 대여가능 수량](https://data.seoul.go.kr/dataList/OA-22382/F/1/datasetView.do).

# 4. 외부 Provider 설계

기존의 포괄적인 `Map Provider` 표현은 사용하지 않는다.

| Provider | 책임 | 구현 예 | 사용 위치 |
|---|---|---|---|
| `PlaceSearchProvider` | 지하철역, 건물, 주소 등 검색 및 좌표 반환 | 네이버 지도/검색 API | 장소 검색 |
| `WalkingRouteProvider` | 출발지↔대여소, 대여소↔목적지 도보 경로 계산 | 지원 가능한 경로 API | 복합 길찾기 |
| `BicycleRouteProvider` | 대여소 간 자전거 이동 경로 계산 | 자전거 경로 API 또는 자체 경로 엔진 | 복합 길찾기 |
| `PredictionProvider` | 특정 대여소의 미래 자전거 수 예측 | Python AI Service | 수요 예측 |

Application Service는 특정 외부 업체 API에 직접 의존하지 않는다.

```text
RouteService
   ├── WalkingRouteProvider
   └── BicycleRouteProvider
```

따라서 도보 경로와 자전거 경로를 서로 다른 외부 서비스로 구현해도 API Contract는 유지할 수 있다.

---

Provider 선정 시 장소 검색·지오코딩·도보·자전거 경로의 지원 여부를 각각 확인한다. 특정 지도/검색 업체가 이 기능을 모두 지원한다고 가정하지 않는다. 검색 좌표는 WGS84로 변환하고 HTML 강조 태그를 제거한다. Provider의 저장·재배포·출처 표시 조건에 맞춰 캐시 및 클라이언트 표시 정책을 확정한다.

# 5. API Version 정책

공개 사용자 API는 다음 Base Path를 사용한다.

```text
/api/v1
```

Breaking Change가 필요한 경우:

```text
/api/v1/...
/api/v2/...
```

를 일정 기간 함께 운영한다.

이후 최소 지원 앱 버전을 상향하여 구버전 앱을 업데이트시킨 뒤 구 API를 폐기한다.

---

# 6. Client Version / 강제 업데이트 정책

앱 버전 확인 API는 `/api/v1` 하위에 두지 않는다.

## `GET /api/client-config`

### Query Parameters

| 이름 | Type | 필수 | 설명 |
|---|---|:---:|---|
| `platform` | String | O | `android`, `ios`, `web` |
| `appVersion` | String | 모바일 O / web X | 출시 버전 `major.minor.patch`, 예: `1.4.0` |
| `buildNumber` | String | X | 플랫폼별 빌드 식별자. iOS 등의 점 구분 값을 보존하며 문자열 사전순으로 비교하지 않음 |

### Request

```http
GET /api/client-config?platform=android&appVersion=1.4.0&buildNumber=140
```

### Response

```json
{
  "success": true,
  "data": {
    "platform": "android",
    "minimumVersion": "1.3.0",
    "latestVersion": "1.5.0",
    "forceUpdate": false,
    "updateAvailable": true,
    "maintenance": false,
    "maintenanceMessage": null,
    "supportedApiVersions": [
      "v1"
    ],
    "storeUrl": "https://play.google.com/store/apps/details?id=..."
  },
  "timestamp": "2026-09-16T21:00:00+09:00",
  "meta": {
    "requestId": "example-request"
  }
}
```

### 버전 판정 계약

Android/iOS는 `appVersion` 누락 또는 잘못된 형식이면 `400 INVALID_PARAMETER`다. 버전은 각 숫자 구성요소로 비교한다. `minimumVersion <= latestVersion`을 설정 검증한다. `forceUpdate = appVersion < minimumVersion`, `updateAvailable = appVersion < latestVersion`이며 강제 업데이트이면 업데이트 가능도 true다. `buildNumber`는 v1에서 진단용이며 판정에 사용하지 않는다. 빌드별 차단이 필요하면 플랫폼별 비교 규칙과 최소/최신 빌드 필드를 추가해야 한다.

`web`은 `forceUpdate=false`, `updateAvailable=false`, `minimumVersion=null`, `latestVersion=null`, `storeUrl=null`로 반환한다. `maintenanceMessage`는 점검 중일 때 사용자 안내 문자열, 나머지는 null이다. 모바일 `storeUrl`은 플랫폼에 맞는 배포 URL이다.

점검 중에도 이 API는 `200`으로 설정을 반환한다. 점검 대상 기능은 서버에서 `503 MAINTENANCE_MODE`로 차단한다. 강제 업데이트 화면은 클라이언트 UX이며 인증·접근 통제 수단이 아니다. 버전 헤더는 신뢰할 수 있는 보안 정보로 사용하지 않는다.

이 API의 응답은 `Cache-Control: no-store`를 기본으로 한다. 설정 조회 실패 시 클라이언트는 재시도와 최근 성공 설정을 활용하며, 실패를 강제 업데이트로 해석하지 않는다. 버전 경로가 없으므로 필드 삭제·의미 변경 없이 하위 호환성을 유지한다.

### 앱 실행 흐름

```text
앱 실행
   │
   ▼
GET /api/client-config
   │
   ├── maintenance = true → 점검 화면
   ├── forceUpdate = true → 강제 업데이트 화면
   └── 정상 → 메인 서비스 진입
```

---

# 7. 공통 Request Header

| Header | 필수 | 설명 |
|---|:---:|---|
| `Accept: application/json` | X | 생략 또는 `*/*` 허용. JSON을 명시적으로 배제하면 406 |
| `Content-Type: application/json` | POST/PATCH 시 O | JSON Request Body |
| `X-Request-Id` | X | 클라이언트가 생성한 추적 ID |
| `X-Client-Platform` | X | `android`, `ios`, `web` |
| `X-Client-Version` | X | 앱 버전 |
| `Accept-Language` | X | 향후 다국어 처리 |

서버는 유효한 `X-Request-Id`만 재사용한다. 허용 형식은 영문·숫자·`-`·`_`의 1~64자이며 누락/부적합 시 UUID를 생성한다. 동일 값을 응답 Header `X-Request-Id`와 `meta.requestId`에 기록한다. 클라이언트 ID를 인증이나 Rate Limit 식별자로 신뢰하지 않는다.

---

# 8. 공통 응답 형식

## 8.1 성공

```json
{
  "success": true,
  "data": {},
  "meta": {
    "requestId": "2e041fe8-1c9e-4a74-b781-6d5db2fa3ed1"
  },
  "timestamp": "2026-09-16T21:00:00+09:00"
}
```

## 8.2 실패

```json
{
  "success": false,
  "error": {
    "code": "INVALID_COORDINATE",
    "message": "유효하지 않은 좌표입니다.",
    "details": []
  },
  "meta": {
    "requestId": "2e041fe8-1c9e-4a74-b781-6d5db2fa3ed1"
  },
  "timestamp": "2026-09-16T21:00:00+09:00"
}
```

운영 환경에서는 Stack Trace, SQL, 내부 클래스명 등 구현 세부정보를 응답하지 않는다.

---

## 8.3 공통 타입 및 오류 계약

성공 시 `success`, `data`, `meta.requestId`, `timestamp`를, 실패 시 `success`, `error`, `meta.requestId`, `timestamp`를 포함한다. 실패에 `data`를 넣지 않고 성공에 `error`를 넣지 않는다. `error.details`는 항상 배열이며 검증 오류 항목은 `{ "field": "radius", "reason": "100~5000의 정수여야 합니다." }` 형태다. 사용자 입력 원문·위치·비밀키는 오류에 반영하지 않는다.

시각은 RFC 3339의 UTC `Z` 또는 명시적 offset을 사용한다. 내부 비교는 같은 시점으로 정규화하고 한국의 시간대별 집계는 `Asia/Seoul` 기준으로 해석한다. `timestamp`는 응답 생성 시각이며 데이터 관측/수집 시각과 구별한다. Query의 `+09:00`은 `%2B09:00`으로 인코딩한다.

좌표는 WGS84의 유한한 숫자이며 위도 -90~90, 경도 -180~180이다. 문자열 숫자·NaN·Infinity를 JSON 숫자 대신 허용하지 않는다. 거리 단위는 m, 기간 단위는 초다. count는 0 이상의 정수이고 null은 알 수 없음을 뜻한다. 배열의 빈 결과는 `[]`, 명시한 nullable 필드는 생략 대신 null을 사용한다. `meta.partial`은 부분 결과를 지원하는 응답에서 사용하는 Boolean이며 생략 시 false로 해석한다. `204`와 Actuator 응답은 공통 JSON envelope 적용 대상이 아니다.

좌표 범위 오류는 `400 INVALID_COORDINATE`, 실제 서비스 영역 제약은 `422 OUT_OF_SERVICE_AREA`로 구별한다. 주변/장소 검색은 지역 밖 또는 결과 없음에 `200`과 빈 배열을 반환할 수 있다. 복합 경로의 서비스 영역은 Provider 지원 범위와 운영 설정으로 명시해야 한다. 미확정 경계를 임의의 서울 직사각형으로 간주하지 않는다.

# 9. HTTP Status 정책

| HTTP Status | 용도 |
|:---:|---|
| `200 OK` | 정상 조회 및 계산 |
| `201 Created` | 리소스 생성 |
| `204 No Content` | 정상 처리, Body 없음 |
| `400 Bad Request` | 요청 형식/파라미터 오류 |
| `401 Unauthorized` | 인증 필요 기능의 미인증 요청 |
| `403 Forbidden` | 권한 부족 |
| `404 Not Found` | 대상 리소스 없음 |
| `406 Not Acceptable` | 허용 가능한 응답 형식 없음 |
| `413 Content Too Large` | 요청 Body 크기 제한 초과 |
| `415 Unsupported Media Type` | 지원하지 않는 요청 Content-Type |
| `409 Conflict` | 중복 또는 상태 충돌 |
| `422 Unprocessable Entity` | 비즈니스 규칙상 처리 불가 |
| `429 Too Many Requests` | Rate Limit 초과 |
| `500 Internal Server Error` | 내부 오류 |
| `502 Bad Gateway` | 외부 Provider 실패 |
| `503 Service Unavailable` | 점검 또는 핵심 의존 서비스 사용 불가 |
| `504 Gateway Timeout` | 외부 Provider Timeout |

---

# 10. 주요 Error Code

| Code | HTTP | 설명 |
|---|:---:|---|
| `INVALID_PARAMETER` | 400 | 요청 파라미터 오류 |
| `INVALID_COORDINATE` | 400 | 좌표 범위 오류 |
| `OUT_OF_SERVICE_AREA` | 422 | 서비스 대상 지역 밖 |
| `INVALID_RADIUS` | 400 | 검색 반경 오류 |
| `INVALID_LIMIT` | 400 | 조회 개수 오류 |
| `PLACE_QUERY_REQUIRED` | 400 | 장소 검색어 누락 |
| `STATION_NOT_FOUND` | 404 | 대여소 없음 |
| `NOT_ACCEPTABLE` | 406 | JSON 응답을 허용하지 않는 Accept |
| `ROUTE_NOT_FOUND` | 404 | 사용 가능한 경로 없음 |
| `NO_AVAILABLE_ORIGIN_STATION` | 422 | 출발지 주변 대여 가능 대여소 없음 |
| `NO_AVAILABLE_RETURN_STATION` | 422 | 목적지 주변 반납 후보 없음 |
| `PREDICTION_NOT_AVAILABLE` | 404 | 예측 데이터 없음 |
| `PREDICTION_OUT_OF_RANGE` | 422 | 지원 예측 범위 밖 |
| `REALTIME_DATA_UNAVAILABLE` | 503 | 요청에 필요한 실시간 데이터 사용 불가 |
| `SERVICE_UNAVAILABLE` | 503 | 기준정보 저장소 등 필수 의존성 장애 |
| `PLACE_PROVIDER_ERROR` | 502 | 장소 검색 Provider 오류 |
| `WALK_ROUTE_PROVIDER_ERROR` | 502 | 도보 경로 Provider 오류 |
| `BIKE_ROUTE_PROVIDER_ERROR` | 502 | 자전거 경로 Provider 오류 |
| `PREDICTION_SERVICE_ERROR` | 502 | AI 예측 서비스 오류 |
| `EXTERNAL_API_TIMEOUT` | 504 | 외부 서비스 Timeout |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | JSON Body API에 지원하지 않는 Content-Type |
| `PAYLOAD_TOO_LARGE` | 413 | 요청 Body 제한 초과 |
| `UNSUPPORTED_ROUTE_OPTION` | 422 | Provider가 요청한 경로 옵션을 지원하지 않음 |
| `RATE_LIMIT_EXCEEDED` | 429 | 호출 제한 초과 |
| `MAINTENANCE_MODE` | 503 | 서비스 점검 중 |
| `INTERNAL_SERVER_ERROR` | 500 | 서버 내부 오류 |

---

# 11. Station API

## 11.0 대여소 공통 필드와 최신성

| 필드 | Type / nullable | 의미 |
|---|---|---|
| `stationId`, `stationName` | String / 불가 | 검증된 식별자와 선택된 조회 스냅샷의 이름 |
| `stationLatitude`, `stationLongitude` | Number / 불가 | 선택된 조회 스냅샷의 유효 좌표 |
| `rackTotCnt` | Integer / 가능 | 거치대 수. 누락 시 null이며 반납 가능 수량이 아님 |
| `parkingBikeTotCnt` | Integer / 불가 | 공개 스냅샷의 마지막 유효 수집 시 대여가능 수량. 0은 정상값 |
| `shared` | Number / 가능 | 원천 거치율(%). 100 초과를 자동 오류 처리하거나 100으로 제한하지 않음 |
| `realtimeStatus` | Enum / 불가 | 공개 Station 응답은 `FRESH`, `STALE`; `UNAVAILABLE`은 내부 집계용 |
| `realtimeUpdatedAt` | DateTime / 불가 | 공개 스냅샷을 성공 수집한 원래 시각 |
| `realtimeAgeSeconds` | Integer / 불가 | 응답 시각에서 원래 수집 시각까지의 경과 초(내림, 최솟값 0) |

`bikeList`에 원천 관측 시각이 없으므로 `realtimeUpdatedAt`을 실제 자전거 상태가 변경된 시각으로 표현하지 않는다. 최신성은 **수집 성공 시점 기준**이며 원천 자체 지연은 보장할 수 없다.

제안 기본값: Collector 주기 60초, `freshForSeconds=120`, `maxStaleSeconds=600`. age가 120 이하이면 FRESH, 120 초과 600 이하이면 STALE, 600 초과 또는 유효 수집 이력이 없으면 UNAVAILABLE이다. 기준을 설정으로 관리하고 경계값은 전체 API에서 동일하게 사용한다.

STALE은 마지막 수량과 수집 시각을 함께 반환한다. UNAVAILABLE은 내부 판정/상태 집계에 사용하는 상태이며 이후 공개 정책에 따라 성공 Station 목록에서 제외한다. 수량 미상을 0으로 표현하지 않는다. 실제 조회에 반환하는 수량은 검증된 0 이상의 정수다.

### 공개 대상과 저장소 우선순위 — 확정 정책

대여소 조회는 검증된 운영 상태가 `ACTIVE`인 대여소만 대상으로 한다. `INACTIVE`(폐쇄/운영 중단), `UNKNOWN`(운영 여부 미확인), 검증된 ID 매핑이 없는 대여소는 제외한다. 공개 필수값은 유효한 `stationId`, 공백이 아닌 `stationName`, 유효한 위도·경도다. nullable인 `rackTotCnt`, `shared`가 null인 것만으로 불완전 판정을 하지 않는다.

Redis를 우선 조회하고, 연결 장애/timeout, 키 누락, 손상·필수값 불완전, 수량 제공 상한 초과이면 해당 ID에 한해 MySQL의 `station_latest_snapshot`을 Batch 조회한다. MySQL은 기준정보·이력과 마지막 유효 스냅샷을 보존하는 영구 저장소다. fallback은 검증된 하나의 스냅샷 단위로 수행하며 Redis의 이름과 MySQL의 수량처럼 서로 다른 시점의 필드를 임의로 섞지 않는다. Redis에서 사용할 수 있는 데이터가 있으면 MySQL 값으로 덮어쓰지 않는다.

최종 선택값은 공개 필수값과 유효한 수량·수집 시각을 갖고 제공 상한 이내여야 한다. 두 저장소에서 모두 사용할 수 있는 스냅샷이 없으면 목록과 경로 후보에서 제외한다. 실시간 필수값이 없거나 상한을 넘긴 UNAVAILABLE 항목은 성공 Station 객체로 노출하지 않는다. STALE은 불완전 데이터와 구별하여 일반 조회에서 경고와 함께 반환할 수 있다. `FRESH`이면서 `parkingBikeTotCnt=0`은 완전하고 정상인 데이터이며 일반 조회에서 0으로 반환한다. 미상/조회 실패를 0으로 바꾸지 않는다.

fallback에도 **원래 수집 시각**을 유지하고 같은 120/600초 기준으로 최신성을 판정한다. SQL 조회 시각이나 Redis 재적재 시각을 `realtimeUpdatedAt`으로 덮어쓰지 않는다. 조회 응답에는 저장소 기술명을 추가하지 않고 `meta.warnings`에 필요한 경우 `FALLBACK_USED`를 제공한다.

운영 상태·공개 가능 여부는 Redis의 공개용 스냅샷에 내부 필드 `operationStatus`, `publicationEligible`로 포함하여 gate로 사용한다. 수집 버전은 `snapshotVersion`으로 보관하며 이 내부 필드들은 사용자 DTO에 노출하지 않는다. 폐쇄/기준정보 무효화 시 MySQL 상태와 Redis 공개 gate·Elasticsearch를 동기화하고 기존 Redis 값으로 폐쇄 대여소를 되살리지 않는다. 상태 변경 반영 실패는 재시도·경보 대상으로 하며, 공개 가능 여부를 신뢰할 수 없으면 MySQL 확인 또는 제외로 처리한다. STALE/수량 0을 폐쇄로 해석하지 않는다.

MySQL에서도 폐쇄/불완전 레코드는 삭제하지 않고 이력 보존용으로 유지한다. 위 제외 정책은 주변·상세·Batch·경로의 출발/도착 후보·예측 대상에 동일하게 적용한다. 장소 검색 결과 자체에는 적용하지 않는다.

### 조회 결과 결정표

아래의 후보는 폐쇄·운영 미확인·기준정보 불완전 대여소를 제외한 후보를 뜻한다.

| 조건 | 일반 주변 조회 | onlyAvailable=true | 대여 가능 출발지 필수 경로 |
|---|---|---|---|
| 후보 없음 | 200, 빈 목록 | 200, 빈 목록 | 422 NO_AVAILABLE_ORIGIN_STATION |
| 모든 후보가 FRESH·0대 | 200, 0대 항목 반환 | 200, 빈 목록 | 422 NO_AVAILABLE_ORIGIN_STATION |
| FRESH 양수 존재 | 유효 FRESH/STALE 반환 | FRESH 양수만 반환 | 양수 후보로 계산; 다른 후보가 미확인/STALE이면 partial |
| FRESH·0대와 미확인/STALE 후보 혼재, FRESH 양수 없음 | 유효 항목만 반환, partial | 200, 빈 목록, partial | 503 REALTIME_DATA_UNAVAILABLE |
| 제공 가능한 FRESH가 없고 STALE만 있음 | 200, STALE 목록, partial | 503 REALTIME_DATA_UNAVAILABLE | 503 REALTIME_DATA_UNAVAILABLE |
| 후보는 있지만 두 저장소 모두 유효 스냅샷 없음 | 503 REALTIME_DATA_UNAVAILABLE | 503 REALTIME_DATA_UNAVAILABLE | 503 REALTIME_DATA_UNAVAILABLE |

존재하지 않거나 정책상 비공개인 ID의 상세/예측 요청은 `404 STATION_NOT_FOUND`다. 공개 대상이지만 두 저장소에서 유효 실시간 스냅샷을 확보하지 못한 상세/예측 요청은 `503 REALTIME_DATA_UNAVAILABLE`다. 기준정보 불완전과 일시적인 실시간 조회 불가는 구별한다.

`meta.partial=true`는 최신성 저하·필요한 후보 확인 실패·Batch 제외 항목 등으로 결과가 불완전한 경우다. 성공한 fallback 자체는 partial을 의미하지 않는다. `meta.warnings`는 선택적 배열이며 생략 시 빈 배열로 해석한다. 원소는 `{ "code": "FALLBACK_USED" }` 형태이고 허용 코드는 `FALLBACK_USED`, `STALE_DATA`, `REALTIME_DATA_UNAVAILABLE`, `STATION_EXCLUDED`, `CANDIDATE_DATA_UNAVAILABLE`, `PROVIDER_PARTIAL_FAILURE`다. 사용자 위치나 내부 저장소 오류 원문은 포함하지 않는다.

## 11.1 주변 대여소 조회

### `GET /api/v1/stations/nearby`

현재 위치 또는 사용자가 선택한 지도 중심 좌표 주변의 따릉이 대여소를 조회한다.

### Query Parameters

| 이름 | Type | 필수 | 기본값 | 제한 | 설명 |
|---|---|:---:|:---:|---|---|
| `latitude` | Double | O | - | `-90 ~ 90` | 기준 위도 |
| `longitude` | Double | O | - | `-180 ~ 180` | 기준 경도 |
| `radius` | Integer | X | `1000` | `100 ~ 5000` | 반경(m) |
| `limit` | Integer | X | `30` | `1 ~ 100` | 최대 반환 수 |
| `sort` | String | X | `distance` | Enum | 정렬 기준 |
| `onlyAvailable` | Boolean | X | `false` | - | 대여 가능한 대여소만 조회 |

### `sort`

| 값 | 의미 |
|---|---|
| `distance` | 가까운 순 |
| `parkingBikeTotCnt` | 자전거주차총건수 많은 순 |
| `name` | 대여소 이름순 |

### Request

```http
GET /api/v1/stations/nearby?latitude=37.4979&longitude=127.0276&radius=1000&limit=30&sort=distance
```

### Response

```json
{
  "success": true,
  "data": {
    "center": {
      "latitude": 37.4979,
      "longitude": 127.0276
    },
    "radiusMeters": 1000,
    "items": [
      {
        "rackTotCnt": 20,
        "stationName": "강남역 2번 출구",
        "parkingBikeTotCnt": 7,
        "shared": 35.0,
        "stationLatitude": 37.4981,
        "stationLongitude": 127.0282,
        "stationId": "ST-123",
        "distanceMeters": 120,
        "realtimeStatus": "FRESH",
        "realtimeUpdatedAt": "2026-09-16T20:59:00+09:00",
        "realtimeAgeSeconds": 60
      }
    ]
  },
  "meta": {
    "requestId": "..."
  },
  "timestamp": "2026-09-16T21:00:00+09:00"
}
```

### 필터·정렬·빈 결과

`onlyAvailable=true`는 FRESH이고 `parkingBikeTotCnt > 0`인 대여소만 포함한다. STALE/UNAVAILABLE은 대여 가능하다고 판정하지 않는다. 먼저 반경 내 전체 후보에 실시간 값을 결합하고 필터와 정렬을 적용한 후 `limit`를 적용한다. Elasticsearch에서 가까운 30개만 잘라 자전거 수 정렬을 하면 반경 전체의 상위 결과가 아니므로 피한다.

`distance`는 직선거리 오름차순, `parkingBikeTotCnt`는 수량 내림차순(null 마지막) 후 거리 오름차순, `name`은 NFC 정규화 이름의 Unicode 코드 포인트 오름차순이다. 최종 동률은 `stationId` 오름차순으로 해소한다. `distanceMeters`는 도보 경로 길이가 아니다. 결과 없음은 `200`과 `items: []`다.

11.0의 공개 대상·fallback·조회 결과 결정표를 적용한다. `partial`은 미확인 후보나 최신성 저하를 나타내며 limit에 따른 잘림과 구별한다. 폐쇄/기준정보 불완전 대여소를 정책대로 제외한 것만으로 일반 주변 조회를 partial로 표시하지 않는다. 최종 선택 스냅샷의 좌표로 반경·거리를 확인한 후 정렬한다.

### Backend 처리

```text
latitude / longitude
        │
        ▼
Elasticsearch geo_distance
        │
        ▼
대여소 목록
        │
        ▼
Redis MGET / Pipeline
        │
        ▼
실시간 상태 Batch 조회 (실패/누락 ID는 MySQL Batch fallback)
        │
        ▼
Application Service에서 결합
```

Redis와 MySQL fallback 모두 Batch로 조회하여 N+1을 피한다. Elasticsearch 장애는 별도 의존성 오류이며 Redis fallback만으로 검색 장애까지 해결된다고 가정하지 않는다.

---

## 11.2 대여소 상세 조회

### `GET /api/v1/stations/{stationId}`

### Response

```json
{
  "success": true,
  "data": {
    "rackTotCnt": 20,
    "stationName": "강남역 2번 출구",
    "parkingBikeTotCnt": 7,
    "shared": 35.0,
    "stationLatitude": 37.4981,
    "stationLongitude": 127.0282,
    "stationId": "ST-123",
    "realtimeStatus": "FRESH",
    "realtimeUpdatedAt": "2026-09-16T20:59:00+09:00",
    "realtimeAgeSeconds": 60
  },
  "meta": {
    "requestId": "..."
  },
  "timestamp": "2026-09-16T21:00:00+09:00"
}
```

---

상세 요청은 11.0의 공개 정책을 따른다. 존재하지 않음/폐쇄/기준정보 불완전은 404, 공개 대상의 유효 스냅샷을 두 저장소에서 모두 확보하지 못하면 503이다. FRESH·0대는 200으로 반환한다.

## 11.3 여러 대여소 실시간 상태 조회

### `GET /api/v1/stations/realtime`

### Query Parameters

| 이름 | Type | 필수 | 제한 |
|---|---|:---:|---|
| `stationIds` | String[] | O | 최대 100개 |

### Request

```http
GET /api/v1/stations/realtime?stationIds=ST-123,ST-124,ST-125
```

### Response

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "rackTotCnt": 20,
        "stationName": "강남역 2번 출구",
        "parkingBikeTotCnt": 7,
        "shared": 35.0,
        "stationLatitude": 37.4981,
        "stationLongitude": 127.0282,
        "stationId": "ST-123",
        "realtimeStatus": "FRESH",
        "realtimeUpdatedAt": "2026-09-16T20:59:00+09:00",
        "realtimeAgeSeconds": 60
      }
    ],
    "notFoundStationIds": ["ST-124", "ST-125"],
    "excludedStationIds": [],
    "unavailableStationIds": []
  },
  "meta": {
    "requestId": "...",
    "partial": true
  },
  "timestamp": "2026-09-16T21:00:00+09:00"
}
```

---

### Batch 요청·부분 결과 계약

`stationIds`는 쉼표로 구분한 단일 Query 문자열이다(예: `ST-123,ST-124`). 공백·빈 항목·형식 오류는 `400 INVALID_PARAMETER`이며 중복 제거 전 1~100개로 제한한다. 중복 ID는 첫 등장만 유지하고 요청 순서를 보존한다. 공개 ID는 `ST-`와 숫자 조합을 사용한다.

`items`에는 공개 가능한 유효 스냅샷만 요청 순서로 반환한다. `data.notFoundStationIds: String[]`에는 존재하지 않는 ID, `data.excludedStationIds: String[]`에는 폐쇄/운영 미확인/기준정보 불완전 ID, `data.unavailableStationIds: String[]`에는 공개 대상이지만 두 저장소에서 유효 스냅샷을 얻지 못한 ID를 넣는다. 세 배열은 항상 포함하고 중복 제거된 요청 순서를 각각 유지한다. 모든 ID는 items 또는 이 세 배열 중 정확히 하나에 속한다.

전부 미존재/정책상 제외이면 `200`과 빈 items다. 공개 대상이 있는데 반환 가능한 항목이 전혀 없으면 `503 REALTIME_DATA_UNAVAILABLE`이다. 하나라도 유효하면 `200`으로 부분 결과를 반환한다. 세 배열이 비어 있지 않거나 반환 항목에 STALE이 있으면 partial=true다. 존재/운영 상태 자체를 판단할 수 없는 저장소 장애는 `503 SERVICE_UNAVAILABLE`이며, 이를 없는 ID로 분류하지 않는다.

# 12. Place Search API

## 12.1 장소 검색

### `GET /api/v1/places/search`

| 이름 | Type | 필수 | 기본값 | 설명 |
|---|---|:---:|:---:|---|
| `query` | String | O | - | 장소명, 역명, 주소 등 |
| `latitude` | Double | X | - | 사용자 현재 위치 |
| `longitude` | Double | X | - | 사용자 현재 위치 |
| `limit` | Integer | X | `10` | 최대 결과 수 |

### Response

```json
{
  "success": true,
  "data": {
    "query": "강남역",
    "items": [
      {
        "placeId": "provider:12345",
        "name": "강남역",
        "category": "SUBWAY_STATION",
        "address": "서울특별시 강남구 ...",
        "roadAddress": "서울특별시 강남구 ...",
        "location": {
          "latitude": 37.4979,
          "longitude": 127.0276
        },
        "distanceMeters": 350
      }
    ]
  },
  "meta": {
    "requestId": "..."
  },
  "timestamp": "2026-09-16T21:00:00+09:00"
}
```

---

### 장소 검색 입력·결과 계약

`query`는 앞뒤 공백 제거 후 1~100 Unicode 코드 포인트(제안 제한)다. 누락/공백이면 `400 PLACE_QUERY_REQUIRED`, 길이 초과이면 `400 INVALID_PARAMETER`다. `limit`는 1~20의 정수(제안 제한)이며 기본 10이다. Provider가 지원하는 결과 수가 더 작으면 가능한 만큼만 반환한다.

좌표는 둘 다 생략하거나 둘 다 제공한다. 한쪽만 제공하면 `400 INVALID_PARAMETER`, 범위 오류는 `400 INVALID_COORDINATE`다. 좌표는 검색 우선순위 힌트이고, 결과의 `distanceMeters`는 그 좌표로부터의 직선거리이며 좌표가 없으면 null이다. 결과가 없으면 `200`과 `items: []`를 반환한다.

`placeId`는 Provider namespace를 포함한 불투명 문자열이며 영구 식별자로 보장하지 않는다. `category`는 `SUBWAY_STATION`, `ADDRESS`, `BUILDING`, `POI`, `OTHER` 중 하나로 정규화한다(제안 분류). `address`, `roadAddress`는 정보가 없으면 null이다. 유효 좌표가 없는 결과는 제외하며 Provider 오류를 빈 검색 결과로 숨기지 않는다.

# 13. Route API

## 13.1 복합 길찾기

### `POST /api/v1/routes/search`

경로 옵션이 확장될 가능성이 높으므로 복잡한 조회 조건은 Request Body로 전달한다.

### Request

```json
{
  "origin": {
    "latitude": 37.4979,
    "longitude": 127.0276
  },
  "destination": {
    "latitude": 37.5665,
    "longitude": 126.9780
  },
  "options": {
    "stationSearchRadiusMeters": 1000,
    "maxWalkingDistanceMeters": 1000,
    "preferBikeRoad": true,
    "excludeStationsWithoutBikes": true,
    "maxCandidates": 5
  }
}
```

### 경로 입력 계약

`origin`, `destination` 및 각 좌표는 필수다. `options` 생략 시 아래 기본값을 적용한다. null·형식 오류는 `400 INVALID_PARAMETER`다. 요청 JSON 크기 상한은 16 KiB를 제안한다.

| 옵션 | Type | 제안 기본값 | 제한/의미 |
|---|---|---|---|
| `stationSearchRadiusMeters` | Integer | 1000 | 100~5000, 출발/도착 각 기준점의 직선 탐색 반경 |
| `maxWalkingDistanceMeters` | Integer | 1000 | 0~10000, 두 WALK 구간의 실제 경로 거리 합계 상한 |
| `preferBikeRoad` | Boolean | true | 자전거도로 선호. 전 구간 자전거도로 보장은 아님 |
| `excludeStationsWithoutBikes` | Boolean | true | true이면 FRESH이고 수량 > 0인 출발 대여소만 허용 |
| `maxCandidates` | Integer | 5 | 1~5, recommended를 포함한 최대 반환 경로 수 |

`excludeStationsWithoutBikes=false`는 탐색 목적이다. 공개 가능한 0대/STALE 출발지도 포함할 수 있으므로 각 경로에 `rentalAvailability`(`AVAILABLE_AT_FETCH`, `UNAVAILABLE_AT_FETCH`, `UNKNOWN`)와 최신성 정보를 포함한다. 이 값은 도착 시점 대여나 예약을 보장하지 않는다. true일 때는 11.0의 결정표를 적용한다. 일부 후보를 확인하지 못했거나 STALE인 상태에서 FRESH·0대만 확인했다고 전체 후보가 0대라고 판정하지 않는다. 이런 경우 503이다. 모든 후보가 FRESH·0대인 경우에만 수량 부족으로 422를 반환한다.

`preferBikeRoad=true`를 Provider가 지원하지 않으면 `422 UNSUPPORTED_ROUTE_OPTION`으로 반환한다. 지원하지 않는 옵션을 조용히 무시하지 않는다. 내부 후보 탐색 개수 및 외부 호출 예산은 반환 수와 분리해 제한한다. 후보를 제한하므로 결과는 탐색한 후보 내 추천 경로이며 전역 최적 경로를 보장하지 않는다.

### 처리 흐름

```text
출발지
  │
  ▼
주변 출발 대여소 후보 Geo Search
  │
  ▼
Redis 실시간 상태 Filter
  │
  ▼
WalkingRouteProvider
  │
  ▼
목적지 주변 반납 대여소 후보
  │
  ▼
BicycleRouteProvider
  │
  ▼
후보 경로 Score 계산
  │
  ▼
탐색 후보 내 추천 경로 + 대안 경로 반환
```

### Response

```json
{
  "success": true,
  "data": {
    "recommended": {
      "routeId": "route-31c649",
      "totalDistanceMeters": 8420,
      "estimatedDurationSeconds": 2280,
      "walkingDistanceMeters": 520,
      "bikeDistanceMeters": 7900,
      "originStation": {
        "stationId": "ST-123",
        "stationName": "강남역 2번 출구",
        "parkingBikeTotCnt": 7,
        "realtimeStatus": "FRESH",
        "realtimeUpdatedAt": "2026-09-16T20:59:00+09:00",
        "realtimeAgeSeconds": 60
      },
      "destinationStation": {
        "stationId": "ST-900",
        "stationName": "시청역 대여소",
        "parkingBikeTotCnt": 4,
        "realtimeStatus": "FRESH",
        "realtimeUpdatedAt": "2026-09-16T20:59:00+09:00",
        "realtimeAgeSeconds": 60
      },
      "segments": [
        {
          "type": "WALK",
          "distanceMeters": 120,
          "durationSeconds": 120,
          "encodedPolyline": "..."
        },
        {
          "type": "BIKE",
          "distanceMeters": 7900,
          "durationSeconds": 1920,
          "bikeRoadPreferred": true,
          "encodedPolyline": "..."
        },
        {
          "type": "WALK",
          "distanceMeters": 400,
          "durationSeconds": 240,
          "encodedPolyline": "..."
        }
      ],
      "rentalAvailability": "AVAILABLE_AT_FETCH",
      "returnAvailability": "UNKNOWN"
    },
    "alternatives": []
  },
  "meta": {
    "requestId": "...",
    "partial": false
  },
  "timestamp": "2026-09-16T21:00:00+09:00"
}
```

### 경로 응답 계약

`recommended`는 성공 응답에 반드시 1개 존재하며 `alternatives`는 최대 `maxCandidates - 1`개다. 기본 추천은 총 소요시간 오름차순, 동률이면 도보거리·총거리·출발/도착 대여소 ID 순이다(제안 규칙). 중복 대여소 쌍의 경로는 제거한다. 각 경로는 WALK → BIKE → WALK 3구간이며 0거리 WALK도 유지한다. 총거리와 총시간은 구간 값의 합이고, 잠금 해제/대기 시간은 포함하지 않는다.

`routeId`는 응답 추적용 ID이며 저장·재조회·예약 가능성을 의미하지 않는다. 두 Station 객체는 예시의 필드에 더해 `realtimeStatus`, `realtimeUpdatedAt`, `realtimeAgeSeconds`를 포함한다. `rentalAvailability`는 출발지 FRESH/양수일 때 AVAILABLE_AT_FETCH, FRESH/0이면 UNAVAILABLE_AT_FETCH, 나머지는 UNKNOWN이다. `returnAvailability`는 신뢰할 수 있는 반납 정보가 없으므로 v1에서 항상 `UNKNOWN`이다.

`encodedPolyline`은 WGS84 좌표를 위도·경도 순서, 소수점 5자리 정밀도로 인코딩한 [Google Encoded Polyline Algorithm](https://developers.google.com/maps/documentation/utilities/polylinealgorithm) 형식으로 통일한다. Provider별 geometry는 Adapter가 변환한다. 위 예시의 `...`는 생략 표시이며 실제 반환 가능한 polyline이 아니다. `bikeRoadPreferred`는 요청 선호가 적용되었는지 나타내며 실제 자전거도로 비율이 아니다.

공개 정책을 통과한 출발 후보 자체가 없으면 `422 NO_AVAILABLE_ORIGIN_STATION`, 도착 후보가 없으면 `422 NO_AVAILABLE_RETURN_STATION`이다. 후보의 일시적 스냅샷 조회 불가로 전부 제외된 경우에는 503으로 구별한다. 후자는 물리적인 반납 슬롯 부족을 뜻하지 않는다. 정상 계산 후 연결 가능한 경로가 없으면 `404 ROUTE_NOT_FOUND`, 외부 장애는 Provider별 502/504로 구별한다. 일부 경로만 계산 성공하면 성공 경로와 `meta.partial=true`, `PROVIDER_PARTIAL_FAILURE` warning을 반환하고 실패한 대안을 정상적인 경로 없음으로 기록하지 않는다.

서울시 실시간 대여정보는 명시적인 반납 가능 슬롯 수를 제공하지 않는다. 따라서 목적지 대여소의 반납 가능 여부를 기본 Route API에서 확정값으로 보장하지 않는다. 별도의 신뢰 가능한 반납 가능 정보가 확보된 경우에만 Route 후보 선정 조건으로 추가한다.

---

# 14. Prediction API

AI는 모든 대여소 데이터를 통합 학습한 Global Model을 기본으로 한다.

```text
stationId
현재 상태
시간
요일
월/계절
Lag Features
Rolling Features
날씨 Feature
대여소 특성
        │
        ▼
Global Model
        │
        ▼
미래 자전거 수
```

## 14.1 특정 시각 예측

### `GET /api/v1/stations/{stationId}/predictions`

| 이름 | Type | 필수 | 설명 |
|---|---|:---:|---|
| `targetAt` | DateTime | O | 예측 대상 시각 |

### Response

```json
{
  "success": true,
  "data": {
    "stationId": "ST-123",
    "generatedAt": "2026-09-16T21:00:00+09:00",
    "targetAt": "2026-09-16T22:00:00+09:00",
    "parkingBikeTotCnt": 7,
    "predictedBikeCount": 4,
    "predictedAvailability": "LOW",
    "confidence": {
      "level": "UNKNOWN"
    },
    "model": {
      "version": "2026.09.1"
    },
    "realtimeStatus": "FRESH",
    "realtimeUpdatedAt": "2026-09-16T20:59:00+09:00",
    "realtimeAgeSeconds": 60
  },
  "meta": {
    "requestId": "..."
  },
  "timestamp": "2026-09-16T21:00:00+09:00"
}
```

예측값은 확정값이 아니므로 UI에서도 `예상`임을 표시한다.

---

## 14.2 시간대별 예측

### `GET /api/v1/stations/{stationId}/predictions/timeline`

| 이름 | Type | 필수 | 기본값 | 설명 |
|---|---|:---:|:---:|---|
| `from` | DateTime | O | - | 예측 시작 시각 |
| `hours` | Integer | X | `6` | 모델 검증 범위 내 조회 시간 |

지원 가능한 최대 시간 `predictionMaxHorizonHours`는 모델 검증 결과로 확정해야 한다. 미확정 상태에서는 Prediction API를 운영 공개하지 않는다. 기본 `hours=6`을 제공하려면 최소 6개 포인트를 지원해야 한다.

### 공통 예측 시간·타입 계약

제안 계약: 예측은 Asia/Seoul의 정시(분·초 0) 단위다. `targetAt`/`from`은 offset을 포함해야 하며 정시가 아니면 `400 INVALID_PARAMETER`다. 요청 검증 시점보다 미래이고 모델의 유효 범위 안에 있어야 한다. 과거/지원 범위 밖은 `422 PREDICTION_OUT_OF_RANGE`다. 임의 반올림하거나 모델 범위를 초과해 외삽하지 않는다.

`predictedBikeCount`는 0 이상의 정수 추정값이며 거치대 수로 상한을 자르지 않는다. 원시 실수 예측은 반올림(0.5 이상 올림) 후 최솟값 0으로 정규화한다(제안 규칙). `predictedAvailability` 제안 구간은 `EMPTY`=0, `LOW`=1~4, `AVAILABLE`=5 이상이다. 기존 예시의 4대/LOW와 일치한다.

`confidence.level`은 `LOW`, `MEDIUM`, `HIGH`, `UNKNOWN`이며 예측 자전거 수와 별개의 모델 품질 지표다. 모델별 평가/보정 기준을 공개하지 못하면 UNKNOWN을 반환한다. confidence는 예약 성공 확률이 아니다. `generatedAt`은 추론 생성 시각이고 캐시 응답마다 갱신하지 않는다. `model.version`은 실제 모델 아티팩트 버전이다.

현재값 `parkingBikeTotCnt`에는 Station API와 같은 공개 대상·fallback 및 최신성 규칙을 적용하고 `realtimeStatus`, `realtimeUpdatedAt`, `realtimeAgeSeconds`를 함께 반환한다. 예측 자체의 유효성은 모델 입력 데이터의 나이와 결측 정책으로 별도 판단한다. 대여소 없음은 404 STATION_NOT_FOUND, 대여소는 있지만 유효 예측 없음은 404 PREDICTION_NOT_AVAILABLE, 추론 장애는 502/504다.

### Timeline 응답

`hours`는 1 이상의 정수이며 **반환할 시간별 포인트 수**다. `from`부터 1시간 간격으로 `hours`개를 반환하며 마지막은 `from + (hours - 1)시간`이다. 모든 포인트가 지원 범위 내여야 한다. 하나라도 유효 예측이 없으면 전체 요청에 PREDICTION_NOT_AVAILABLE을 반환한다(제안: v1 부분 예측 미지원).

```json
{
  "success": true,
  "data": {
    "stationId": "ST-123",
    "generatedAt": "2026-09-16T21:00:00+09:00",
    "from": "2026-09-16T22:00:00+09:00",
    "hours": 2,
    "intervalMinutes": 60,
    "model": {
      "version": "2026.09.1"
    },
    "items": [
      {
        "targetAt": "2026-09-16T22:00:00+09:00",
        "predictedBikeCount": 4,
        "predictedAvailability": "LOW",
        "confidence": {
          "level": "UNKNOWN"
        }
      },
      {
        "targetAt": "2026-09-16T23:00:00+09:00",
        "predictedBikeCount": 6,
        "predictedAvailability": "AVAILABLE",
        "confidence": {
          "level": "UNKNOWN"
        }
      }
    ]
  },
  "meta": {
    "requestId": "example-request"
  },
  "timestamp": "2026-09-16T21:00:00+09:00"
}
```

---

# 15. Service Status API

## `GET /api/v1/status/realtime`

### 정상

```json
{
  "success": true,
  "data": {
    "status": "NORMAL",
    "lastUpdatedAt": "2026-09-16T20:59:00+09:00",
    "ageSeconds": 60,
    "totalStationCount": 3000,
    "freshStationCount": 3000,
    "staleStationCount": 0,
    "unavailableStationCount": 0
  },
  "meta": {
    "requestId": "..."
  },
  "timestamp": "2026-09-16T21:00:00+09:00"
}
```

### 상태

| 상태 | 의미 |
|---|---|
| `NORMAL` | 정상 갱신 |
| `DELAYED` | 기존 Cache 제공 가능하지만 최신성 저하 |
| `UNAVAILABLE` | 제공 가능한 실시간 데이터 없음 |

---

### 집계 상태 계약

이 API 자체가 정상 동작하면 데이터 상태가 UNAVAILABLE이어도 `200`이다. 상태 계산 자체가 불가능하면 `503 REALTIME_DATA_UNAVAILABLE`이다. `lastUpdatedAt`은 **전체 수집이 마지막으로 성공 완료된 시각**, `ageSeconds`는 그 시각 기준 경과 초이며 이력이 없으면 둘 다 null이다. 개별 대여소 최신성과 구분한다.

`data`에 `totalStationCount`, `freshStationCount`, `staleStationCount`, `unavailableStationCount`를 포함한다. ACTIVE이고 ID·이름·좌표가 검증된 공개 대상 대여소를 분모로 하고 뒤의 세 count 합은 total과 같아야 한다. 모두 FRESH이고 대상이 1개 이상이면 NORMAL, 제공 가능한 FRESH/STALE이 일부라도 있으면 DELAYED, 나머지는 UNAVAILABLE이다. 전체 수집 시각 하나만으로 모든 대여소를 정상 판정하지 않는다. 각 대여소는 Redis 우선/MySQL fallback을 적용한 결과로 집계한다. Redis의 전체 수집 시각을 읽을 수 없으면 MySQL ingestion_run의 마지막 전체 SUCCESS 완료 시각을 사용한다. 읽기 장애로 count를 확정하지 못하면 숫자를 추정하지 않고 503을 반환한다.

# 16. Health / Readiness / Liveness

## Liveness

```http
GET /actuator/health/liveness
```

프로세스 자체가 정상 동작하는지 확인한다.

## Readiness

```http
GET /actuator/health/readiness
```

Traffic을 받을 준비가 되어 있는지 확인한다.

Readiness에 아래 의존성을 일괄 포함하지 않는다. 공유 저장소 장애로 모든 인스턴스가 동시에 Traffic에서 제거되는 영향을 검토하여, 그 장애 시 어떤 사용자 기능도 제공할 수 없는 필수 의존성만 포함한다. 의존성 후보는 다음과 같다.

```text
MySQL
Redis
Elasticsearch
```

외부 지도 Provider 하나의 장애 때문에 대여소 조회까지 전체 차단되지 않도록 장애를 격리한다.

Actuator는 외부 인터넷 전체에 공개하지 않는다.

---

Spring Boot의 probe endpoint를 사용할 환경에서는 `management.endpoint.health.probes.enabled=true` 등 명시적인 활성화 구성을 검증한다. Liveness에는 외부 API나 공유 저장소 상태를 넣지 않는다. 근거: [Spring Boot Actuator 문서](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html). Actuator 응답은 Spring Boot 형식이며 8장의 공통 envelope를 적용하지 않는다. 배포 시 실제 사용하는 포트의 probe 접근과 실패 시 503을 확인한다.

# 17. 앱/웹 최초 진입 흐름

서비스의 **기본 진입점은 장소 검색이 아니라 현재 위치 기반 주변 대여소 조회**다.

```text
앱 / 웹 실행
      │
      ▼
GET /api/client-config
      │
      ├── 점검 여부 확인
      ├── 강제 업데이트 여부 확인
      └── API 지원 버전 확인
      │
      ▼
위치 권한 확인
      │
      ├── 거부 → 장소 직접 검색 UI 제공
      │
      └── 허용
            │
            ▼
      클라이언트 GPS 위치 획득
            │
            ▼
GET /api/v1/stations/nearby
    ?latitude={현재위도}
    &longitude={현재경도}
    &radius=1000
            │
            ├── Elasticsearch → 주변 대여소 Geo Search
            └── Redis → 실시간 상태 Batch 조회 (실패/누락 ID는 MySQL Batch fallback)
            │
            ▼
지도 + 대여소 Marker + 대여 가능 대수
```

현재 위치는 클라이언트가 획득하며 Backend가 기기의 GPS를 직접 가져오지 않는다.

---

# 18. 지도 이동 후 재검색

```text
지도 이동
   │
   ▼
지도 중심 좌표 변경
   │
   ▼
"이 지역에서 검색"
   │
   ▼
GET /api/v1/stations/nearby
   │
   ▼
새 지역 대여소 표시
```

Drag 이벤트마다 요청하지 않고 Debounce 또는 `이 지역에서 검색` UX를 사용한다.

---

# 19. 장소 직접 검색 흐름

```text
사용자 "강남역" 입력
       │
       ▼
GET /api/v1/places/search
       │
       ▼
PlaceSearchProvider
       │
       ▼
검색 결과 선택
       │
       ▼
선택 위치 latitude / longitude
       │
       ▼
GET /api/v1/stations/nearby
       │
       ▼
주변 대여소 + 실시간 대여 가능 대수
```

현재 위치, 검색 위치, 지도 중심 위치 모두 동일한 `/stations/nearby`를 재사용한다.

---

# 20. 실시간 정보 갱신 정책

```text
TanStack Query
      │
      │ 적절한 refetch interval
      ▼
GET /api/v1/stations/realtime
      │
      ▼
Redis
```

Client Polling은 제안 기본 60초이며 Collector 주기와 맞춘다. 앱이 백그라운드이거나 화면이 비활성이면 중단하고, 네트워크 오류와 429에는 Retry-After 및 지수 Backoff/jitter를 적용한다. 한 화면의 동일 대여소 요청을 합치고 지도 이동 요청은 취소하거나 최신 응답만 반영한다.

---

# 21. Data Collector

## 21.1 과거 학습 데이터

대여소별 테이블을 생성하지 않는다.

```text
station_status_history
```

정규화 컬럼:

```text
id
station_id
station_dt_raw
station_date_time
rack_tot_cnt
station_name
parking_bike_tot_cnt
shared
station_latitude
station_longitude
source_dataset
collected_at
```

Mapping:

```text
stationId            → station_id
stationDt            → station_dt_raw
rackTotCnt           → rack_tot_cnt
stationName          → station_name
parkingBikeTotCnt    → parking_bike_tot_cnt
shared               → shared
stationLatitude      → station_latitude
stationLongitude     → station_longitude
```

`station_date_time`은 `stationDt`를 조회 및 AI 학습용 DateTime으로 변환한 파생 컬럼이다.

중복 방지:

```sql
UNIQUE (station_id, station_date_time)
```

조회 최적화:

```sql
-- UNIQUE (station_id, station_date_time)가 동일 순서의 조회 인덱스 역할도 하므로 중복 인덱스를 만들지 않는다.
```

AI는 대여소마다 별도 모델을 생성하지 않고 전체 대여소 데이터를 통합한 Global Model을 우선 사용한다.

Raw 적재와 정규화 학습 테이블의 중복 키는 구분한다. 위 UNIQUE는 시간 단위로 선택된 Canonical 값에 적용한다. 서로 다른 원천의 같은 시각 데이터는 Raw에서 `source_dataset`·원본 파일·행 식별자를 보존하고 충돌 시 자동 덮어쓰기하지 않는다. 원천 우선순위와 정정 규칙을 확정한 후 Canonical을 갱신한다. 분 단위 실시간 수집값을 이 시간별 값과 같은 의미로 섞지 않는다. `station_dt_raw`의 보존과 누락 시간대 탐지를 수행하고 미래 데이터가 학습 Feature에 유입되지 않도록 한다.

## 21.2 실시간 Redis

```text
station:realtime:{stationId}
```

Redis Value는 원천 실시간 API의 Row 필드명을 유지한다.

```json
{
  "rackTotCnt": 20,
  "stationName": "강남역 2번 출구",
  "parkingBikeTotCnt": 7,
  "shared": 35.0,
  "stationLatitude": 37.4981,
  "stationLongitude": 127.0282,
  "stationId": "ST-123",
  "realtimeUpdatedAt": "2026-09-16T20:59:00+09:00"
}
```

전체 수집 상태:

```text
station:realtime:last-success-at
station:realtime:last-attempt-at
```

성공 시각과 마지막 시도 시각을 분리한다. `last-success-at`은 예정된 전체 페이지의 수집·검증·저장이 완료된 경우에만 갱신한다. 일부 성공 시 개별 대여소 값만 갱신할 수 있지만 전체 성공 시각은 유지하며 실패 페이지·누락 ID·수집 run ID를 기록한다. 빈/오류 행으로 마지막 유효값을 지우거나 그 시각을 새로 고치지 않는다. 늦게 완료된 이전 수집 작업이 새 값을 덮어쓰지 않도록 버전/시각 비교를 원자적으로 수행한다. Collector 다중 인스턴스에서는 분산 락 또는 단일 실행자 정책을 둔다.

위 JSON을 Redis String으로 저장할 경우 MGET을 사용한다. Hash로 저장하는 구현이면 HGETALL 등의 Pipeline을 사용하며 MGET과 혼용하지 않는다. 값과 TTL은 원자적으로 갱신한다.

---

### 필수 영구 보존과 MySQL fallback

Collector는 검증된 최신 실시간 행을 MySQL `station_latest_snapshot`에 대여소별 1건으로 보존한다. 같은 원래 수집 시각과 버전으로 Redis 공개용 스냅샷을 갱신한다. MySQL 저장 완료 후 Redis 반영을 기본 순서로 하며 Redis 반영 실패는 재시도한다. 두 저장소 간 분산 트랜잭션을 가정하지 않고 수집 버전 비교로 늦은 쓰기를 차단한다. MySQL 보존 실패는 수집 성공으로 처리하지 않으며 기존 Redis 값은 자신의 유효기간 동안 계속 제공할 수 있다.

시간별 `station_status_history`와 최신 `station_latest_snapshot`은 목적을 분리한다. 시간별 이력의 새 import 시각을 현재 실시간 수집 시각처럼 사용하지 않는다. MySQL fallback은 최신 스냅샷을 사용하며 분/시간별 Raw 전체 스캔을 하지 않는다.

채택된 시간별 이력·최신 스냅샷의 **출처 메타데이터**(`source_record`, 연결된 `ingestion_run`)는 필수다. 원천·수집 run·파일/행 위치·해시·수집 시각을 보존하고 FK로 연결한다. 원문 본체는 별도 `source_payload` 또는 Raw 파일에 보관하며 보존 기간과 저장 위치를 따로 관리한다. 참조 중인 출처 메타데이터는 삭제하지 않고 원문 본체 만료가 이력 FK를 끊지 않게 한다. 기간 만료 후 원문 재처리가 불가능할 수 있음을 기록한다.

`station_identifier_mapping`에는 검증 완료되고 유효기간이 확인된 매핑만 저장한다. 검증 대기 후보는 `source_record.validation_status=UNMAPPED`와 구조화된 validation_errors에 후보 ID·사유를 기록하고, 원문 보존 기간 안에 재검증한다. 현재 확인 시각을 과거 매핑 시작일로 임의 사용하지 않는다.

# 22. Cache 정책

| 대상 | 저장소 | 정책 |
|---|---|---|
| 대여소 기준 데이터·최신 스냅샷 | MySQL | 영구 보존 및 Redis 미조회 시 Batch fallback |
| 대여소 검색 Index | Elasticsearch | MySQL 기반 동기화 |
| 실시간 자전거 상태 | Redis | 신선도와 물리 TTL 분리; 제안 TTL 900초, STALE 제공 상한 600초 |
| 장기 시간대별 상태 | MySQL | `OA-22382` 초기 적재 + `OA-20447` 최근 데이터 보완 |
| 대여/반납 흐름 | MySQL/분석 저장소 | `OA-21229` 원천 컬럼 보존 후 Feature 생성 |
| 장소 검색 결과 | Redis 선택 | 짧은 TTL |
| 예측 결과 | Redis 선택 | `stationId + targetAt + modelVersion + featureSnapshotVersion` 기준 또는 입력 갱신 시 무효화 |
| Client Config | Memory/Redis | 짧은 TTL 가능 |

오래된 실시간 Cache를 즉시 삭제하기보다 마지막 갱신 시각과 함께 제공하여 일정 시간 Graceful Degradation 할 수 있다.

---

TTL은 `maxStaleSeconds`보다 길게 두되 TTL 안에 남아 있어도 유효기간을 넘긴 수량은 사용하지 않고 MySQL fallback 후에도 미상이면 11.0의 제외/오류 규칙을 적용한다. Redis 자체 장애/키 누락에는 MySQL의 마지막 유효 스냅샷으로 fallback하며 동일한 데이터 제공 상한을 적용한다. 위치 Query와 경로 Body가 포함된 사용자 응답은 `Cache-Control: no-store`를 기본으로 하며, 서버 내부 캐시는 Provider 약관과 데이터 최신성 한도 내에서만 사용한다.

# 23. Timeout / Retry / Circuit Breaker

외부 Provider 호출은 무제한 대기하지 않는다.

| 대상 | 정책 |
|---|---|
| Place Provider | 짧은 Connect/Read Timeout |
| Walking Route Provider | 제한된 Timeout |
| Bicycle Route Provider | 제한된 Timeout |
| Prediction Service | 제한된 Timeout |
| 서울시 API Collector | Retry + Backoff |

```text
Timeout / 5xx → 제한적 Retry 가능
4xx           → 원칙적으로 Retry하지 않음. 429는 Retry-After와 남은 deadline 내에서만 재시도
```

연속 장애 시 Circuit Breaker를 적용할 수 있다.

---

재시도는 안전한 조회와 멱등 호출에 한정하고 전체 요청 deadline에 포함한다. 계층마다 중복 재시도하지 않는다. 제안 기본 예산: 연결 1초, 장소/예측 전체 3초, 복합 경로 전체 10초, 일시 실패 재시도 최대 1회. 실제 Provider SLA·과금·부하 검증으로 확정한다. Circuit Open은 해당 Provider의 502, 시간 초과는 504로 구별하고 출처 없는 직선 경로를 정상 자전거 경로로 대체하지 않는다.

# 24. Rate Limit

실서비스에서는 공개 API 남용을 방지한다.

| API | 정책 방향 |
|---|---|
| `/stations/nearby` | IP/Client 단위 제한 |
| `/places/search` | 외부 API 비용을 고려해 더 강한 제한 |
| `/routes/search` | 계산 비용이 높아 강한 제한 |
| `/predictions` | Cache와 함께 제한 |
| `/client-config` | 높은 허용량 |

초과 시:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 30
```

실제 수치는 Traffic 측정 후 결정한다.

---

Rate Limit은 클라이언트가 임의 변경 가능한 헤더만으로 적용하지 않는다. IP 판정은 신뢰한 reverse proxy의 forwarding header만 사용하고 공용 NAT 환경을 고려한다. 여러 인스턴스의 제한 카운터를 공유하며 실제 API별 수치/시간창과 Redis 장애 시 정책은 운영 공개 전에 확정한다.

# 25. CORS / Security

Web은 운영 도메인만 허용한다.

```text
https://service.example.com
```

운영에서는 `Access-Control-Allow-Origin: *`를 사용하지 않는다.

외부 API Key는 가능한 한 Backend Provider Adapter에서 관리하여 Web/React Native 코드에 직접 노출하지 않는다.

---

CORS는 브라우저 동작 정책이며 인증이나 봇 방어가 아니다. 허용 Origin에 대해 GET/POST/OPTIONS 및 문서의 요청 헤더를 허용하고, `X-Request-Id`, `Retry-After`, `Deprecation`, `Sunset`은 노출 응답 헤더로 설정한다. 쿠키 인증을 사용하지 않는 현재 API는 credentials를 기본 비활성화한다. 비밀 서버 API Key와 공개가 전제된 지도 SDK Key를 구분하고 후자에는 도메인/앱 제한을 적용한다.

# 26. 개인정보 최소화

비회원 핵심 서비스에서 사용자의 현재 위치를 DB에 영구 저장할 필요는 없다.

```text
현재 위치
→ 주변 대여소 검색 Query
→ 응답
→ 영구 저장하지 않음
```

운영 로그에도 정확한 위·경도를 불필요하게 원문으로 남기지 않는다.

---

# 27. Pagination / 결과 제한

Geo Search는 Page 기반 Pagination보다:

```text
radius + limit
```

을 사용한다.

일반 대규모 리스트 API가 추가되면 Cursor Pagination 적용을 우선 검토한다.

---

# 28. DTO 정책

JPA Entity, Elasticsearch Document, Redis Object를 API Response로 직접 반환하지 않는다.

Java 21 `record`를 적극 사용한다.

```java
public record LocationResponse(
        double latitude,
        double longitude
) {
}
```

```java
public record RealtimeStationResponse(
        Integer rackTotCnt,
        String stationName,
        Integer parkingBikeTotCnt,
        Double shared,
        double stationLatitude,
        double stationLongitude,
        String stationId,
        RealtimeStatus realtimeStatus,
        OffsetDateTime realtimeUpdatedAt,
        Long realtimeAgeSeconds
) {
}
```

---

# 29. Controller 구성

```text
presentation
│
├── StationController
│   ├── getNearbyStations()
│   ├── getStation()
│   └── getRealtimeStatuses()
│
├── PlaceSearchController
│   └── searchPlaces()
│
├── RouteController
│   └── searchRoutes()
│
├── PredictionController
│   ├── getPrediction()
│   └── getPredictionTimeline()
│
├── ServiceStatusController
│   └── getRealtimeStatus()
│
└── ClientConfigController
    └── getClientConfig()
```

Controller는 Redis, Elasticsearch, JPA, 외부 API를 직접 호출하지 않는다.

---

# 30. Domain과 API Mapping

| Domain | 주요 역할 | API |
|---|---|---|
| `station` | 대여소 정보 및 현재 상태 | `/stations/*` |
| `search` | 장소 검색 | `/places/search` |
| `route` | 복합 길찾기 | `/routes/search` |
| `prediction` | AI 예측 | `/stations/{id}/predictions*` |
| `status` | 실시간 데이터 상태 | `/status/realtime` |
| `clientconfig` | 앱 버전/점검 정책 | `/api/client-config` |

---

# 31. Endpoint Summary

| Domain | Method | Endpoint | 인증 | 설명 |
|---|:---:|---|:---:|---|
| Client | GET | `/api/client-config` | X | 앱 버전, 강제 업데이트, 점검 정보 |
| Station | GET | `/api/v1/stations/nearby` | X | 현재/선택 위치 주변 대여소 |
| Station | GET | `/api/v1/stations/{stationId}` | X | 대여소 상세 |
| Station | GET | `/api/v1/stations/realtime` | X | 여러 대여소 실시간 상태 |
| Place | GET | `/api/v1/places/search` | X | 장소/역/주소 검색 |
| Route | POST | `/api/v1/routes/search` | X | 도보 + 자전거 복합 경로 |
| Prediction | GET | `/api/v1/stations/{stationId}/predictions` | X | 특정 시각 예측 |
| Prediction | GET | `/api/v1/stations/{stationId}/predictions/timeline` | X | 시간대별 예측 |
| Status | GET | `/api/v1/status/realtime` | X | 실시간 원천 데이터 상태 |
| Operations | GET | `/actuator/health/liveness` | 내부 | Liveness |
| Operations | GET | `/actuator/health/readiness` | 내부 | Readiness |

---

# 32. API Deprecation 정책

```text
v1 운영
  │
  ▼
v2 출시
  │
  ├── 기존 앱 → v1
  └── 신규 앱 → v2
  │
  ▼
전환 기간
  │
  ▼
minimumVersion 상향
  │
  ▼
구버전 앱 강제 업데이트
  │
  ▼
v1 Traffic 제거 확인
  │
  ▼
v1 폐기
```

Deprecated API에는 가능한 경우 다음 Header를 제공한다.

```text
Deprecation: @1688169599
Sunset: Wed, 30 Jun 2027 23:59:59 GMT
```

`Deprecation`은 Boolean이 아니라 `@` 뒤 Unix 초를 사용하는 Structured Field Date다. 위 값은 형식 설명용 과거 시각이며 실제 배포에서는 공지한 폐기 권고 시각으로 교체한다. `Sunset`은 실제 응답 종료 예정 시각의 HTTP-date이며 Deprecation 시각보다 앞서지 않아야 한다. 근거: [RFC 9745](https://www.rfc-editor.org/rfc/rfc9745.html). 종료 정책과 마이그레이션 문서 링크를 함께 공지한다.

실제 삭제 전에 Monitoring으로 Traffic이 남아 있는지 확인한다.

---

# 33. Observability

## Metrics

```text
API 요청 수
HTTP Status별 비율
p50 / p95 / p99 Latency
Elasticsearch Latency
Redis Latency
외부 Provider 성공률
외부 Provider Timeout 수
실시간 데이터 최신성
Prediction Service Latency
```

## Structured Logging

```json
{
  "requestId": "...",
  "method": "GET",
  "path": "/api/v1/stations/nearby",
  "status": 200,
  "elapsedMs": 42
}
```

민감정보와 불필요한 정확한 위치 데이터는 로그에 남기지 않는다.

---

# 34. GitHub Actions CI/CD

Jenkins는 사용하지 않는다.

```text
GitHub main Push
       │
       ▼
GitHub Actions
       │
       ├── Test
       ├── Gradle Build
       ├── Docker Build
       └── Security / Quality Check
       │
       ▼
AWS ECR
       │
       ├── commit SHA tag
       └── latest tag
       │
       ▼
Application EC2
       │
       ▼
신규 Container 시작
       │
       ▼
Readiness Check
       │
       ├── Success → Traffic 전환 / 기존 버전 정리
       └── Fail    → 신규 버전 폐기 / 기존 버전 유지
```

AWS 인증은 가능하면:

```text
GitHub Actions OIDC
        ↓
AWS IAM Role
```

구성을 사용한다.

---

단일 EC2에서 새 컨테이너를 먼저 준비시키려면 서로 다른 포트와 Reverse Proxy/Load Balancer 전환 절차가 필요하다. 동일 포트 컨테이너 교체만으로 무중단을 보장하지 않는다. DB 변경은 이전 버전과 호환되는 expand/contract 방식으로 수행하며 Image 롤백이 DB rollback을 자동 보장하지 않는다.

# 35. Docker Image 정책

```text
main-service:<git-commit-sha>
main-service:latest
```

실제 배포는 Image digest를 고정하거나 ECR tag immutability를 설정한 Commit SHA Tag를 사용한다. SHA처럼 생긴 Tag도 덮어쓰기가 가능하므로 이름만으로 불변성을 보장하지 않는다.

문제가 발생하면 이전 SHA Image로 롤백한다.

ECR Lifecycle Policy로 오래된 Image를 정리하되 최근 롤백용 Image는 보관한다.

---

# 36. 실제 서비스 전체 사용자 흐름

```text
                         앱 / 웹 실행
                              │
                              ▼
                     GET /api/client-config
                              │
             ┌────────────────┼─────────────────┐
             │                │                 │
             ▼                ▼                 ▼
        점검 화면        강제 업데이트       정상 진입
                                                │
                                                ▼
                                         현재 위치 획득
                                                │
                                                ▼
                                  GET /stations/nearby
                                                │
                                 ┌──────────────┴──────────────┐
                                 ▼                             ▼
                         Elasticsearch                     Redis
                       주변 대여소 검색                실시간 상태 조회
                                 │                             │
                                 └──────────────┬──────────────┘
                                                ▼
                                지도 + 대여 가능 대수 표시
                                                │
                     ┌──────────────────────────┼──────────────────────────┐
                     │                          │                          │
                     ▼                          ▼                          ▼
                대여소 선택                지도 이동                 장소 검색
                     │                          │                          │
                     ▼                          ▼                          ▼
               상세 / 예측             이 지역에서 검색        /places/search
                     │                          │                          │
                     └──────────────────────────┴──────────────┬───────────┘
                                                              ▼
                                                       목적지 선택
                                                              │
                                                              ▼
                                                POST /routes/search
                                                              │
                                     ┌────────────────────────┴────────────────────────┐
                                     ▼                                                 ▼
                           WalkingRouteProvider                              BicycleRouteProvider
                                     │                                                 │
                                     └────────────────────────┬────────────────────────┘
                                                              ▼
                                                   도보 + 자전거 경로
```

---

# 37. 핵심 설계 결정 요약

| 항목 | 실제 서비스 기준 결정 |
|---|---|
| API 버전 | `/api/v1` 사용, Breaking Change 시 `v2` 병행 |
| 앱 강제 업데이트 | `/api/client-config`로 별도 관리 |
| 메인 진입 화면 | 현재 위치 기준 주변 대여소 자동 표시 |
| 기본 검색 반경 | 현재 명세상 1km, UX 테스트 후 조정 가능 |
| 위치 획득 | Client가 GPS 획득, Backend는 좌표만 전달받음 |
| 대여소 Geo Search | Elasticsearch |
| 실시간 자전거 수 | Redis 우선, MySQL 최신 스냅샷 fallback |
| 공공데이터 필드명 | `rackTotCnt`, `parkingBikeTotCnt`, `shared`, `stationLatitude`, `stationLongitude`, `stationId`, `stationName` 우선 유지 |
| 영구 데이터 | MySQL: 기준정보·최신 스냅샷·시간별 이력·필수 출처 메타데이터 |
| 대여소 이력 | 하나의 History Table + `station_id` |
| AI | 전체 대여소 통합 Global Model |
| 장소 검색 | `PlaceSearchProvider` |
| 도보 경로 | `WalkingRouteProvider` |
| 자전거 경로 | `BicycleRouteProvider` |
| 실시간 갱신 | REST Batch Refresh, 필요 시 SSE/WebSocket 검토 |
| 장애 대응 | Timeout / Retry / Circuit Breaker / Graceful Degradation |
| API 남용 대응 | Rate Limit |
| Health Check | Actuator Liveness / Readiness |
| 관측성 | Metrics + Structured Logs + Request ID |
| CI/CD | GitHub Actions |
| AWS 인증 | GitHub Actions OIDC + IAM Role 기준 |
| Docker Tag | Git Commit SHA + `latest` |
| 개인정보 | 현재 위치의 불필요한 영구 저장 금지 |

---

# 38. 향후 회원 기능 도입 시

현재 핵심 API는 비회원으로 동작한다.

회원 기능이 필요한 시점에는 다음 도메인을 추가한다.

```text
auth
user
favorite
ride
course
feedback
```

예:

```text
POST /api/v1/auth/login
POST /api/v1/auth/refresh

GET  /api/v1/users/me

GET    /api/v1/users/me/favorite-stations
POST   /api/v1/users/me/favorite-stations
DELETE /api/v1/users/me/favorite-stations/{stationId}

POST /api/v1/rides
GET  /api/v1/rides/{rideId}
```

회원 기능 추가 후에도 기존 대여소 검색 및 길찾기 API는 인증 없이 사용할 수 있도록 유지하는 것을 기본 방향으로 한다.

---

---

# 39. 운영 고도화 — Kubernetes

기본 배포 구조는 다음과 같다.

```text
GitHub Actions
      ↓
Docker Image Build
      ↓
AWS ECR
      ↓
Application EC2
      ↓
Docker Container 실행
```

다음과 같은 운영 요구가 증가할 경우 Kubernetes 기반 오케스트레이션을 적용할 수 있다.

```text
트래픽 증가
독립 서비스 증가
무중단 배포 필요성 증가
서비스별 독립적인 Scale-out 요구
컨테이너 장애 자동 복구 필요
다중 서버 운영 필요
배포/복구 자동화 수준 고도화
```

## 39.1 Kubernetes를 고려할 수 있는 이유

서비스는 여러 독립 Application 및 데이터 저장소로 구성된다.

```text
Spring Boot Main Service
AI Prediction Service
Data Collector
Redis
Elasticsearch
MySQL
외부 Route / Place Provider
```

이 중 특히 Spring Boot Main Service, AI Prediction Service, 일부 Collector는 서로 다른 부하와 장애 특성을 가진 독립적인 Application Workload다.

서비스 규모가 커질 경우 다음 문제가 발생할 수 있다.

| 운영 문제 | Kubernetes 적용 시 기대 효과 |
|---|---|
| Spring Boot Container 장애 | Pod 자동 재생성(Self-healing) |
| 새 버전 배포 시 중단 위험 | Rolling Update |
| 특정 시간대 API Traffic 증가 | Spring Boot Pod만 Scale-out |
| AI 추론 요청 증가 | AI Service만 독립 Scale-out |
| 여러 Application Instance 주소 관리 | Kubernetes Service / Service Discovery |
| 새 Pod가 준비되기 전 Traffic 유입 | Readiness Probe로 차단 |
| 비정상 Container가 계속 실행됨 | Liveness Probe 기반 재시작 |
| CPU/Memory 사용량 급증 | HPA를 통한 수평 확장 |
| Node 장애 | 다른 Node에서 Workload 재배치 가능 |

Kubernetes의 도입 목적은 서로 독립적인 Application의 장애·배포·확장을 개별적으로 관리하고 전체 서비스 가용성을 높이는 것이다.

---

## 39.2 Stateless Application 우선 적용

Kubernetes 도입 시 모든 구성 요소를 Cluster 내부로 이전할 필요는 없다.

우선 대상은 Stateless Application이다.

### Stateless

중요한 사용자 데이터나 서비스 상태를 Application Instance의 로컬 메모리/디스크에 영구적으로 보존하지 않는 Application.

예:

```text
Spring Boot API
AI Prediction API
일부 Data Collector
```

Spring Boot가 다음과 같이 동작한다면 Stateless하게 운영할 수 있다.

```text
Request
   ↓
Spring Boot
   ├── MySQL 조회
   ├── Redis 조회
   ├── Elasticsearch 조회
   └── 외부 Service 호출
   ↓
Response
```

어느 Spring Boot Pod가 요청을 받아도 동일한 결과를 제공할 수 있어야 한다.

### Stateful

중요한 상태나 데이터를 지속적으로 보존해야 하는 시스템.

```text
MySQL
Redis
Elasticsearch
```

이들은 단순히 Pod Replica를 여러 개 만드는 것만으로 안정성이 해결되지 않는다.

다음 항목까지 함께 고려해야 한다.

```text
Persistent Volume
Replication
Data Consistency
Backup
Failover
Recovery
```

따라서 Kubernetes 도입 초기에는 다음 구조의 적용을 우선 검토한다.

```text
Kubernetes
├── Spring Boot Main Service × N
├── AI Prediction Service × N
└── 필요한 Application Workload

외부 상태 저장 시스템
├── RDS MySQL
├── Redis
└── Elasticsearch
```

Stateless Application을 우선 Kubernetes에서 운영하고 Stateful System은 별도의 데이터 지속성 및 복구 정책에 따라 관리한다.

---

## 39.3 장애 격리 관점

서비스의 목표는 모든 구성요소가 항상 동시에 정상이어야만 동작하는 구조를 만드는 것이 아니다.

가능한 경우 일부 서비스 장애가 전체 장애로 이어지지 않도록 한다.

예:

```text
AI Prediction Service 장애
        ↓
수요 예측 기능 일시 중단

대여소 검색           정상
실시간 자전거 정보    정상
지도 조회             정상
길찾기                정상
```

또는:

```text
PlaceSearchProvider 장애
        ↓
장소 직접 검색 사용 불가

현재 위치 기반 대여소 조회   정상
지도 탐색                   정상
대여소 상세                 정상
```

Kubernetes는 Application Workload의 자동 복구와 Replica 운영을 통해 이런 장애 격리 전략을 보조할 수 있다.

단, Kubernetes 자체가 서비스 간 장애 격리를 자동으로 해결해주는 것은 아니며 Application 설계에서 Timeout, Circuit Breaker, Fallback 등의 정책도 함께 적용해야 한다.

---

## 39.4 예상 Kubernetes 구조

서비스가 충분히 성장한 이후 다음 구조를 적용할 수 있다.

```text
                         Internet
                            │
                            ▼
                         Ingress
                            │
                            ▼
                    Spring Service
                            │
               ┌────────────┼────────────┐
               ▼            ▼            ▼
          Main Pod       Main Pod      Main Pod
               │
               │ internal service
               ▼
          Prediction Service
               │
          ┌────┴────┐
          ▼         ▼
       AI Pod     AI Pod


외부 상태 저장소

RDS MySQL
Redis
Elasticsearch
```

여기서 각 Application은 독립적으로 Replica 수를 조절할 수 있다.

예:

```text
Spring Boot
replicas: 3

AI Service
replicas: 2
```

Spring Boot Traffic이 증가해도 AI Service까지 무조건 같은 수로 확장할 필요가 없다.

---

## 39.5 적용 시 검증할 기능

Kubernetes를 실제 적용한다면 단순히 Cluster에 Container를 올리는 것에서 끝내지 않고 다음 항목을 검증한다.

| 항목 | 검증 내용 |
|---|---|
| Replica | Spring Boot를 2개 이상 실행 |
| Self-healing | Pod 강제 종료 후 자동 복구 확인 |
| Readiness Probe | 준비되지 않은 Pod에 Traffic이 전달되지 않는지 확인 |
| Liveness Probe | 비정상 Pod가 재시작되는지 확인 |
| Rolling Update | 서비스 중단 최소화 상태로 신규 Version 배포 |
| Service Discovery | Spring Boot ↔ AI Service 내부 통신 |
| HPA | 부하 증가 시 Pod 자동 증가 |
| Multi-Node | Node 장애 시 다른 Node로 Workload 이동 |
| Load Test | k6/JMeter 등을 이용해 Scale-out 효과 측정 |

---

## 39.6 GitHub Actions와 Kubernetes

현재 CI/CD 도구는 GitHub Actions를 유지한다.

Kubernetes 도입 이후에는 배포 대상만 변경된다.

현재:

```text
GitHub Push
    ↓
GitHub Actions
    ↓
Test / Build
    ↓
Docker Image
    ↓
ECR
    ↓
EC2 Container 교체
```

Kubernetes 도입 후:

```text
GitHub Push
    ↓
GitHub Actions
    ↓
Test / Build
    ↓
Docker Image
    ↓
ECR
    ↓
Kubernetes Deployment Update
    ↓
Rolling Update
```

따라서 Docker Image Build 및 ECR 저장 전략은 그대로 재사용할 수 있다.

---

## 39.7 Kubernetes 도입 판단 기준

다음 조건 중 여러 항목이 실제로 발생하면 Kubernetes 도입을 적극 검토한다.

```text
□ Spring Boot Instance를 여러 대 운영해야 한다.
□ Application 장애 자동 복구가 중요해졌다.
□ 무중단 또는 Rolling Deployment 요구가 커졌다.
□ AI Service와 Main API의 Scale-out 요구가 다르다.
□ 여러 EC2의 Container를 수동으로 관리하기 어려워졌다.
□ Traffic 변화 폭이 커 자동 확장이 필요하다.
□ 서비스 수가 증가하여 Service Discovery가 필요하다.
□ 장애/배포/확장 자동화를 통한 운영 효율 개선 효과가 명확하다.
```

반대로 단일 또는 소규모 EC2 + Docker 환경으로 충분히 안정적인 경우 Kubernetes를 억지로 도입하지 않는다.

---

# 40. 최종 원칙

> **클라이언트는 위치와 사용자 입력을 전달하고, Backend는 검색·실시간 데이터·외부 경로·AI 예측을 조합하여 안정된 API Contract를 제공한다.**

실제 서비스에서는 기능 구현뿐 아니라 다음을 Backend 설계의 일부로 취급한다.

```text
호환성
장애 격리
데이터 최신성
API 비용 통제
보안
관측성
롤백 가능성
클라이언트 업데이트 정책
```

운영 환경은 처음부터 가장 복잡한 기술을 적용하는 것이 아니라 서비스 규모와 실제 요구에 맞춰 단계적으로 고도화한다.

```text
초기
GitHub Actions + Docker + EC2

        ↓
서비스 성장 / 운영 요구 증가

확장
Kubernetes 기반 Application Orchestration
```

Kubernetes는 서비스의 가용성·확장성·배포 안정성 요구에 따라 적용하는 운영 고도화 구성으로 정의한다.


---

# 41. 운영 공개 전 확정할 항목

| 항목 | 현재 문서의 상태 | 확정/검증 기준 |
|---|---|---|
| 최신성 및 TTL | 120/600/900초 제안 | 원천 지연 측정과 장애 UX 검증 |
| Route/Place Provider | 업체 미정 | 도보·자전거·자전거도로 옵션, WGS84 변환, 비용, 이용약관 |
| 서비스 영역 | 경계 미정 | 지원 지역과 Provider 범위 설정 및 사용자 안내 |
| 모델 예측 범위 | 최대 horizon 미정 | 시간대별 오차 평가; 확정 전 API 운영 공개 불가 |
| 예측 confidence | 모델별 기준 미정 | 검증 전 UNKNOWN 반환 |
| API별 Rate Limit | 수치 미정 | 허용 횟수·시간창·제한 저장소 장애 정책 |
| 외부 호출 deadline | 제안값 | SLA와 부하 테스트로 확정 |
| 원천 데이터 스키마 | 일부 확인 대기 | 인증 샘플 및 원본 CSV 헤더·식별자·단위 확인 |
| 데이터 출처 충돌 | 선택 규칙 미정 | Raw 보존과 Canonical 우선순위/정정 정책 |

문서의 JSON 예시는 구조 설명이며 표/본문의 필드 계약이 우선한다. 실제 구현 전 OpenAPI 3.1 문서에 required, nullable, enum, HTTP 응답과 예시를 옮겨 기계 검증해야 한다. 이 검토본은 운영 배포 완료나 외부 Provider 연결 검증을 의미하지 않는다.
