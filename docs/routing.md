# 따릉라이더 길찾기 실행 안내

목표 경로는 **현재 위치 또는 출발 장소 → 대여 대여소 → 목적지 주변 반납 대여소 → 목적지**, 이동 방식은 **도보 → 자전거 → 도보**입니다. 카카오 지도 JavaScript SDK로 지도를 표시하고, 카카오 Local API로 장소·주소를 검색하며 카카오맵 도보·자전거 REST API로 실제 도로 경로를 계산합니다. 서비스 안에서 경로를 표시합니다.

## 인증과 실행

같은 [카카오 Developers 앱](https://developers.kakao.com/)에서 다음 두 키를 발급하고 카카오맵 사용을 활성화합니다. 프로젝트 최상위 `.env`에 입력합니다.

```dotenv
KAKAO_MAPS_JAVASCRIPT_KEY=발급받은_JavaScript_키
KAKAO_MAPS_REST_API_KEY=발급받은_REST_API_키
```

JavaScript SDK 도메인에는 포트를 포함한 `http://localhost:5173`, `http://127.0.0.1:5173` 및 실제 사용하는 LAN/배포 주소를 등록합니다. JavaScript 키는 브라우저에서 사용하며 REST 키는 서버에만 전달합니다. REST 키에 `VITE_` 접두사를 붙이지 않습니다. 기본 카카오 구성에서는 네이버 인증키나 별도의 ORS 키가 필요하지 않습니다.

처음 실행할 때는 프로젝트 최상위에서 다음 명령을 사용합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Mode Real
```

이미 실행 중인 서버에서 코드 또는 REST 키 변경을 반영하려면 다음 명령을 사용합니다. 웹의 JavaScript 키 변경은 Vite 재시작이 필요합니다.

```powershell
docker compose --env-file .env --profile real up -d --no-deps --build --wait --wait-timeout 120 backend
```

`http://localhost:5173/`에서 이용합니다. 인증 점검은 `node scripts/check-kakao-auth.mjs`, PC·모바일 브라우저 점검은 `node scripts/check-kakao-web.mjs`로 실행할 수 있습니다. 두 점검 스크립트의 브라우저 검증에는 Edge 및 `.local/browser-check/node_modules`에 설치한 Playwright가 필요합니다. 외부 API 점검은 실제 이용량을 사용합니다. 결과와 캡처는 `.local`에 저장합니다.

## 화면에서 이용하기

- **길찾기**에서 출발지 또는 목적지 입력란을 누르면 그 항목이 선택 대상이 됩니다. 입력란 테두리와 공용 도구의 **출발지 선택 중 / 목적지 선택 중** 표시로 대상을 확인합니다. **현재 위치**, **지도에서 선택**, 즐겨찾기 대여소는 선택된 항목에만 적용되며 버튼을 중복 표시하지 않습니다.
- 현재 위치는 고정밀·새 위치를 요청하며 브라우저가 보고한 정확도를 표시합니다. 기기가 제공한 좌표 자체가 실제 위치와 다를 수 있습니다. **지도에서 선택**을 누르고 지도를 움직여 중앙 핀을 맞춘 뒤, 대상에 따라 **이 위치에서 출발 / 이 위치로 도착**으로 지정할 수 있습니다. 출발지와 목적지 모두 주소를 확인해 표시합니다.
- 장소를 검색하거나 공용 도구로 출발지와 목적지를 지정한 후 **따릉이 경로 찾기**를 누릅니다. 검색어 입력 후 검색 결과 선택이 필요합니다.
- 검색한 장소의 **출발지로 / 도착지로**는 해당 장소 좌표를 경로의 시작·끝으로 지정합니다. 대여소 상세의 **여기서 출발 / 여기로 도착**은 해당 대여소를 대여·반납 지점으로 고정합니다. **자동 선택**으로 되돌릴 수 있으며, 출발지·목적지 수정 시 해당 쪽의 지정만 해제됩니다. 반납 지점은 자전거가 0대여도 지정할 수 있지만 최신 정보가 필요하며, 실제 반납 가능 여부는 현장에서 확인해야 합니다.
- 총 거리·예상 시간, 세 구간의 안내, 대여·반납 대여소와 경로선을 표시합니다. 도보는 파란 점선, 자전거는 청록색 실선이며 한국어 안내는 카카오의 응답을 사용합니다.
- PC에서는 왼쪽 패널에서 결과를 확인합니다. 모바일에서는 검색 후 하단 패널을 중간 높이로 바꾸고 위쪽 지도에 전체 경로를 맞춥니다. 결과에서 위로 스크롤하면 출발·도착 입력을 수정할 수 있습니다.
- 출발지나 목적지가 바뀌면 이전 경로와 선을 지웁니다. AI 예측 영역은 대여소 상세에 유지합니다. 아직 예측 서비스가 연결되지 않은 경우 준비 안내를 표시합니다.
- 출발지를 입력·변경하거나 현재 위치·지도 위치로 다시 지정하면 이전 지정 대여소는 해제하고 자동 선택으로 되돌립니다. 목적지만 변경할 때는 지정 대여소를 유지합니다.

휴대폰 LAN HTTP 접속에서는 장소 검색으로 사용할 수 있습니다. 휴대폰의 현재 위치 조회에는 HTTPS 또는 그 기기의 localhost가 필요합니다.

## 대여소 선택과 경로 계산

자동 선택은 출발·도착 위치의 직선 반경 500m 안에서 각 최대 3개 후보를 선택합니다. 사용 가능한 후보가 없으면 1km로 확대합니다. 출발 후보는 FRESH(수집 시각 기준 120초 이하, `realtime.freshness.fresh-seconds`) 수량이 1대 이상이어야 하며 STALE·UNAVAILABLE 대여소는 제외합니다. 반납 후보와 지정 반납 대여소는 UNAVAILABLE(600초 초과 또는 수집 이력 없음)만 제외하고 STALE(120초 초과 600초 이하)도 허용합니다(명세 11.0, 반납 가능 여부는 v1에서 항상 UNKNOWN). 출발 후보 중 FRESH·1대 이상이 없을 때, 모든 후보가 FRESH·0대이면 '대여 가능한 자전거 없음'(422), STALE·UNAVAILABLE이 하나라도 섞여 있으면 수량을 확인할 수 없으므로 503입니다. FRESH 120초는 수집기의 60초 주기 실시간 Redis 갱신 job(명세 11.0·21.2, R2)을 전제로 합니다. 5분 주기 수집만으로는 대부분 STALE이 되어 출발 후보가 없고 길찾기가 503으로 실패합니다. 직접 지정한 출발 대여소는 자동 선택 반경과 무관하게 ID로 조회합니다. 지정 대여소의 수량이 없거나 확인되지 않으면 해당 원인을 안내하며 임의로 다른 대여소를 지정하지 않습니다. 주변 대여소 없음·대여 가능한 자전거 없음·최신 수량 확인 불가는 각각 다른 오류로 안내합니다.

후보별 실제 도보·자전거 경로를 조회해 세 구간의 예상 시간 합이 가장 짧은 조합을 선택합니다. 같은 대여소를 출발·반납 지점으로 선택하지 않습니다. 연결할 수 없는 조합은 제외하며 직선거리로 경로나 시간을 대체하지 않습니다. 후보가 양쪽 모두 3개라면 도보 최대 6회, 자전거 최대 9회로 검색 한 건에 최대 15회 호출합니다. 선택된 구간은 그 요청에서 이미 조회한 응답을 사용하므로 최종 표시를 위해 다시 호출하지 않습니다. 응답은 해당 검색 요청 동안에만 보관하며 다른 요청과 공유하거나 DB·Redis에 저장하지 않습니다.

최종 경로를 계산한 뒤 출발 대여소의 수량을 다시 조회합니다. 수량이 없어지거나 정보가 오래됐으면 다시 검색하도록 알립니다. 서비스 자체의 출발·목적지 50km 제한은 2026-10-11 제거했습니다. 대여소가 있고 공급자가 연결 가능한 경로를 반환하는 조건으로 판단합니다. 실제 대여 수량이 없는 샘플 프로필에서는 길찾기를 비활성화합니다.

반납 후보는 FRESH 또는 STALE 수량 정보가 있는 목적지 주변 대여소이며 대여 수량 0인 곳도 포함될 수 있습니다. 현재 데이터만으로 반납 공간을 확정할 수 없으므로 `거치대 수 - 대여 수량`을 빈자리라고 보장하지 않습니다. 대여·반납에 드는 시간은 예상 이동 시간에 포함되지 않습니다. 이동 중 GPS에 따른 자동 재탐색이나 음성 안내는 현재 구현에 포함되지 않습니다.

## API

모든 응답은 공통 형식(명세 8장)을 따릅니다. 성공은 `{ "success": true, "data": …, "meta": { "requestId": "…" }, "timestamp": "…" }`, 실패는 `{ "success": false, "error": { "code", "message", "details" }, "meta": { "requestId" }, "timestamp" }`이며 실패 응답에는 `data`가 없습니다.

- `GET /api/v1/routes/status`: `{ "success": true, "data": { "configured": true 또는 false }, "meta": { "requestId": "…" }, "timestamp": "…" }`. 키 설정 여부이며 인증 성공 여부를 의미하지 않습니다. 키 자체는 반환하지 않습니다.
- `POST /api/v1/routes/plan`: 카카오 장소 검색·현재 위치에서 얻은 좌표를 전달합니다. 좌표를 직접 입력하는 사용자 화면은 없습니다.

```json
{
  "origin": { "latitude": 37.4979, "longitude": 127.0276 },
  "destination": { "latitude": 37.5006, "longitude": 127.0364 },
  "departureStationId": "선택 사항: 출발 대여소 ID",
  "arrivalStationId": "선택 사항: 도착 대여소 ID"
}
```

성공 응답의 `data`에는 `origin`, `destination`, `departureStation`, `arrivalStation`(대여소 공통 형식), `legs`(walk/bicycle/walk), 구간별 `path`, `steps`, 거리(m), 시간(s), 생성 시각(`generatedAt`)과 출처(`attribution`)가 포함됩니다.

실패는 `error.code`로 구분합니다(HTTP 상태는 코드 기준, `GlobalExceptionHandler`·`ErrorCode`).

| 상황 | HTTP | `error.code` |
|---|---|---|
| 좌표 범위 위반 / 필드 누락·형식 오류·대여소 ID 길이 초과 | 400 | `INVALID_COORDINATE` / `INVALID_PARAMETER` |
| 출발지 주변 대여소 없음, 대여 가능한 자전거 없음(모든 후보가 FRESH·0대), 지정 출발 대여소 0대(`PINNED_STATION_EMPTY`) | 422 | `NO_AVAILABLE_ORIGIN_STATION` |
| 목적지 주변 반납 대여소 없음 | 422 | `NO_AVAILABLE_RETURN_STATION` |
| 지정 대여소를 찾지 못함 | 404 | `STATION_NOT_FOUND` |
| 연결되는 도보·자전거 경로 없음(후보 조합 전부 미연결 또는 공급자가 경로 없음 응답) | 404 | `ROUTE_NOT_FOUND` |
| 도보 구간 공급자 장애·한도 초과·인증 실패 | 502 | `WALK_ROUTE_PROVIDER_ERROR` |
| 자전거 구간 공급자 장애·한도 초과·인증 실패 | 502 | `BIKE_ROUTE_PROVIDER_ERROR` |
| 후보·지정 대여소의 최신 수량 확인 불가(출발: FRESH·1대 이상 없음 + STALE/UNAVAILABLE 포함, 반납: 전부 UNAVAILABLE) | 503 | `REALTIME_DATA_UNAVAILABLE` |
| 외부 경로 공급자 응답 지연(요청 전체 deadline 10초 초과 포함) | 504 | `EXTERNAL_API_TIMEOUT` |
| 요청 한도 초과(routes 분당 10, burst 10, `Retry-After` 헤더) | 429 | `RATE_LIMIT_EXCEEDED` |
| 길찾기 키 미설정·비활성, 데이터 저장소(Redis/DB) 장애 | 503 | `SERVICE_UNAVAILABLE` |
| 경로 계산 후 출발 대여소 수량 변경(확장 코드) | 409 | `RENTAL_STOCK_CHANGED` |

외부 응답 본문이나 인증 정보는 사용자에게 전달하지 않으며 상세 원인은 요청 ID와 함께 서버 로그에만 남깁니다.

### 경로 공급자 구조

`PlanBikeRoute`는 구간별 포트 `WalkingRouteProvider`(도보)와 `BicycleRouteProvider`(자전거)만 사용합니다. 각 포트의 구현은 엔진 어댑터 `EngineWalkingRouteProvider`·`EngineBicycleRouteProvider`이며, `routing.provider`로 선택한 하나의 엔진(`RouteProvider` 내부 SPI: 기본 카카오 `KakaoRouteClient`, 선택 시 ORS `OpenRouteServiceClient`)에 프로필(`foot-walking`/`cycling-regular`)을 고정해 호출합니다. 어댑터는 엔진의 공급자 실패 코드(`ROUTING_UNAVAILABLE`/`ROUTING_LIMIT`/`ROUTING_AUTH`)를 구간별 `WALK_ROUTE_PROVIDER_ERROR`/`BIKE_ROUTE_PROVIDER_ERROR`(502)로 바꾸고, `NO_ROAD_ROUTE`(→ `ROUTE_NOT_FOUND`)와 `ROUTING_NOT_CONFIGURED`(→ `SERVICE_UNAVAILABLE`)는 그대로 전달합니다. 어댑터를 거치지 않은 엔진 실패 코드가 핸들러에 도달하면 안전망으로 `BIKE_ROUTE_PROVIDER_ERROR`(502)로 응답합니다.

## 실제 검증 (2026-10-10)

카카오 장소 검색, 도보·자전거 REST 인증, 등록한 localhost 도메인에서 JavaScript SDK와 실제 지도 타일 로딩을 확인했습니다. PC 1600×900(16:9) 및 모바일 390×844 브라우저에서 실제 서울시 대여 수량과 카카오 API로 강남역 주변 → 역삼역 경로를 계산했습니다. 결과는 1,054m·661초였으며 지도에 세 구간 경로선과 네 지점을 표시했습니다. 브라우저 위치는 테스트용 강남역 좌표로 설정했고 검색·대여 수량·경로 응답은 실제 API를 사용했습니다.

두 화면에서 AI 영역 유지, 즐겨찾기, 확대·축소, 목적지 변경 후 경로 제거를 확인했습니다. JavaScript 오류와 가로 넘침, 네이버 SDK 요청은 없었습니다. PC에서는 드래그 중 대여소 마커가 지도와 함께 움직이고 새 영역의 대여소를 추가 조회하는 것도 확인했습니다. 실제 휴대폰의 GPS 정확도와 현장 주행은 검증하지 않았습니다.

백엔드 자동 테스트와 웹 테스트·운영 빌드로 후보 시간 비교, 최신 대여 수량 검사, 경로 없음, 좌표 순서, 요청 안에서만 경로 재사용, 인증·한도·빈 응답 오류 처리를 검증합니다. 테스트 결과는 프로젝트 최상위 README의 카카오 전환 검증 기록을 참고하세요.

## 공식 문서

- [카카오맵 사용 설정](https://developers.kakao.com/docs/ko/kakaomap/common)
- [카카오 웹 지도 SDK 가이드](https://apis.map.kakao.com/web/guide/)
- [지도·오버레이·Polyline SDK 문서](https://apis.map.kakao.com/web/documentation/)
- [카카오 Local 장소·주소 검색](https://developers.kakao.com/docs/latest/ko/local/dev-guide)
- [카카오맵 도보·자전거 REST API](https://developers.kakao.com/docs/ko/kakaomap/rest-api)
- [카카오 API 이용량](https://developers.kakao.com/docs/ko/getting-started/quota)

ORS 구현은 선택 가능한 기존 공급자로 남아 있지만 기본 실행에서는 사용하지 않습니다. 선택하려면 별도로 `routing.provider=ors`와 `ORS_API_KEY`를 설정해야 합니다.


### 공용 선택 도구 검증

`node scripts/check-shared-route-tools.mjs`는 PC·모바일에서 즐겨찾기, 0대인 반납 대여소, 현재 위치, 지도 선택을 출발지와 목적지 양쪽에 적용하고 반대쪽 값 유지, 경로 요청의 대여소 ID, 늦게 도착한 위치 응답 무시를 검증합니다. `CHECK_BASE_URL`로 테스트 서버 주소를 지정할 수 있습니다. 장소·대여소·경로 API와 위치 응답은 고정 응답을 사용하며 지도 SDK는 실제로 로드합니다.


### 경로의 대여소 마커

경로 검색을 실행하면 일반 대여소 마커와 수량 범례를 숨기되, 직접 지정한 대여·반납 대여소는 검색 중에도 유지한다. 결과가 나오면 실제 경로의 출발/도착 대여소 두 곳만 수량 마커로 표시한다. 두 지점이 같은 대여소이면 한 번만 표시하며, 지도를 축소해도 경로 대여소는 클러스터로 합치지 않는다. 마커를 눌러 대여소 상세를 확인할 수 있다. 입력을 변경해 경로가 해제되면 기존 주변 대여소 표시로 돌아간다.

브라우저 검증: `node scripts/check-route-station-markers.mjs` (`CHECK_BASE_URL` 지정 가능).
