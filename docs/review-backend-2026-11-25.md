# 백엔드 코드 검토 — 2026-11-25

검토 범위: `develop`(`4b3d051`)의 `Ddareungi-rider/backend` 전체(소스 149개, 약 9,400줄). `main` 대비 백엔드 파일 대부분이 이 브랜치에서 바뀌어 사실상 백엔드 코드 전체를 대상으로 봤습니다. 검토 수준은 extra-high입니다. 11건을 보고했고, 그중 10건을 수정했습니다. 10번은 테스트로 확인한 결과 의도된 동작이라 수정하지 않았습니다(오탐).

- 중요(이 브랜치에서 생김): 2건
- 브랜치 이전부터 있던 문제: 3건
- 경미: 6건(그중 1건 오탐)

모바일 앱 검토는 [review-2026-11-25.md](review-2026-11-25.md)에 있습니다.

## 중요

### 1. 재고 이력 요청이 몰리면 API 전체가 멈출 수 있음

- 위치: `station/infrastructure/InventoryDayCache.java`
- 원인: 캐시 미스 처리를 `synchronized` 블록 안에서 했고, 그 안에서 Redis 조회·MySQL 조회·Redis 저장을 합니다. 이 앱은 Java 21(`eclipse-temurin:21-jre`)에서 `spring.threads.virtual.enabled: true`로 실행됩니다. JDK 21에서는 가상 스레드가 `synchronized` 안에서 블록되거나 그 모니터 진입을 기다리면 실제 OS 스레드(carrier)를 붙잡습니다.
- 증상: 2코어 서버에서 캐시 미스 2건이 MySQL을 기다리면 carrier 2개가 모두 묶여, 다른 모든 요청(주변 대여소, 길찾기, 헬스 체크)이 그 쿼리가 끝날 때까지 멈춥니다. 모바일 상세 화면의 「시간대별 수량」은 30초마다 이 API를 호출합니다.
- 수정: `ReentrantLock` 64개 스트라이프로 바꿨습니다. 가상 스레드는 `ReentrantLock` 대기와 그 안의 I/O 중에 carrier를 놓습니다. 키별 중복 조회를 막는 동작은 그대로입니다.

### 2. 늦게 들어온 재고 기록이 비교표에서 계속 빠짐

- 위치: `station/infrastructure/InventoryPreloader.java`, `station/infrastructure/InventoryDayCache.java`
- 원인: 사전 적재는 매 30초 `마지막 체크포인트 - 10분 ~ 현재`만 다시 읽었습니다. 한 번 '준비 완료'된 날짜는 `PreloadedInventoryStore.read`가 Redis에서 바로 답하고 MySQL로 돌아가지 않습니다. 그래서 수집 슬롯보다 10분 넘게 늦게 기록된 행(수집기 장애 후 backfill 등)은 Redis에 들어가지 않았습니다. 테이블에 기록 시각 컬럼이 없어 '새로 들어온 행'만 골라 읽을 수도 없습니다.
- 증상: 수집기가 10:00~10:30에 멈췄다가 10:31에 10:00~10:25 슬롯을 채우면, 10:00~10:15 슬롯이 최대 8일 동안 비교표에서 비어 보였습니다.
- 수정: 첫 실행과 그 뒤 10분마다 최근 3시간 구간을 다시 복사합니다(`LATE_ARRIVAL_WINDOW`, `LATE_RESCAN_INTERVAL`). 복사는 멱등입니다. 사전 적재가 없을 때 쓰는 Redis 일자 캐시도 어제 날짜는 6시간 대신 10분만 보관합니다.

## 브랜치 이전부터 있던 문제

모두 서울시 실시간 대여정보(OA-15493 `bikeList`) 수집 경로입니다. 문서상 이 수집은 수집기로 옮길 예정(R1, 보류)이지만 지금은 백엔드가 매분 수집합니다.

### 3. 비정상 행 하나로 전체 실시간 갱신이 실패함

- 위치: `realtime/infrastructure/SeoulBikeRealtimeClient.java`
- 원인: 대여 수량(`parkingBikeTotCnt`)이 비었거나 숫자가 아닌 행이 하나라도 있으면 예외가 나 그 분의 갱신 전체를 건너뛰었습니다.
- 증상: 그런 행이 피드에 계속 있으면 모든 대여소가 2분 뒤 '갱신 지연', 10분 뒤 '정보 없음'(503)이 됩니다.
- 수정: 행 단위로 검증해 잘못된 행만 건너뜁니다(그 대여소는 '0대'가 아니라 '확인 불가'로 남음). 읽은 행 대부분이 잘못됐거나 유효한 행이 없으면 응답 형식 변경으로 보고 기존처럼 실패시킵니다.

### 4. 대여소 수가 페이지 크기의 배수면 전체 갱신이 실패함

- 위치: 같은 파일
- 원인: 1000건 단위로 읽으며 "가득 찬 페이지 다음"을 계속 요청합니다. 대여소가 정확히 3000곳이면 네 번째 요청이 범위를 벗어나 `INFO-200`(데이터 없음)을 받고, 성공 코드가 아니라서 앞 세 페이지까지 버렸습니다.
- 수정: 두 번째 페이지부터 `INFO-200`은 목록의 끝으로 처리합니다. 서비스 래퍼 없이 오는 형태(`{"CODE":"INFO-200"}`, `{"RESULT":{"CODE":...}}`)도 읽습니다. 첫 페이지의 `INFO-200`은 기존처럼 오류입니다.

### 5. 좌표가 빠진 대여소가 (0, 0)으로 이동함

- 위치: 같은 파일, `realtime/infrastructure/RealtimeSnapshotValues.java`
- 원인: 수집기는 `asDouble()`로 빈 좌표를 0.0으로 바꿨고, 스냅샷 파서는 위도·경도 범위만 검사했습니다. 대여소 검색(`CatalogStationSearch.join`)은 실시간 좌표로 카탈로그 좌표를 덮어씁니다.
- 증상: 그 대여소가 지도·주변 목록에서 사라지고, 출발 대여소로 지정하면 (0, 0)에서 경로를 찾아 실패합니다.
- 수정: 수집 시 좌표가 없거나 숫자가 아니거나 (0, 0)인 행을 건너뜁니다. 이미 Redis에 있는 값도 (0, 0)이면 파싱 단계에서 버려 카탈로그 좌표를 씁니다.

## 경미

### 6. 결과 없는 장소 검색이 오류(502·504)로 바뀜

- 위치: `place/application/PlaceSearchService.java`
- 원인: 키워드 검색이 0건이면 주소 검색을 보조로 호출하는데, 이 호출은 예외 처리 밖에 있었습니다. 클래스 설명("보조 조회는 best effort")과 달랐습니다.
- 수정: 주소 보조 조회가 실패하면 결과 없음(빈 목록)으로 답합니다.

### 7. 카카오 응답 항목 하나로 장소 목록 전체가 실패함

- 위치: `place/infrastructure/KakaoPlaceSearchProvider.java`
- 원인: 이름이 비었거나 좌표가 잘못된 항목 하나가 응답 전체를 실패시켰습니다.
- 수정: 읽을 수 없는 항목만 건너뜁니다. 항목은 있는데 하나도 읽지 못하면 응답 형식 문제로 보고 실패시킵니다(기존 테스트 계약 유지).

### 8. 대여소 batch 요청에서 좌표를 빼면 (0, 0) 기준으로 거리를 계산함

- 위치: `station/presentation/StationController.java`(`StationBatchRequest`)
- 원인: 위도·경도가 기본형 `double`이라, 빠지면 0.0으로 채워졌습니다.
- 수정: `@NotNull Double`로 바꿔 빠지면 400 `INVALID_PARAMETER`로 답합니다. 웹과 앱은 항상 좌표를 보내므로 기존 호출에는 영향이 없습니다(명세에 없는 확장 API).

### 9. 시간 초과 뒤에도 외부 호출 동시 실행 수가 bulkhead 상한을 넘을 수 있음

- 위치: `global/resilience/ResilientCaller.java`, `global/resilience/ContextPropagation.java`
- 원인: 시간 초과된 요청을 취소(`cancel(true)`)한 직후 bulkhead 슬롯을 반납했습니다. 실제 HTTP 요청은 아직 진행 중일 수 있습니다.
- 수정: 슬롯을 호출자와 시도 스레드가 함께 잡고, 마지막 시도 스레드가 실제로 끝날 때 반납합니다. 시작 전에 취소된 작업도 스레드 종료 시점에 반납되도록 `ContextPropagation.start`에 종료 콜백을 추가했습니다.

### 10. 반납 접근 지점 투영이 구간 뒤쪽 연장선까지 나감 — 오탐, 수정하지 않음

- 위치: `routing/PlanBikeRoute.java`(`projectOntoApproach`)
- 검토 내용: 투영 비율을 1에서만 자르고 0에서는 자르지 않아, 대여소가 도보 첫 구간의 뒤쪽에 있으면 연장선 위의 점이 나옵니다.
- 확인 결과: 의도된 동작입니다. 경로 제공자가 도보 시작점을 대여소에서 조금 떨어진 도로 지점으로 붙이면, 같은 도로에서 대여소와 가장 가까운 지점은 시작점의 뒤쪽에 있습니다. 기존 `PlanBikeRouteTest.reroutesBothLegsToTheSameRoadAccessNearTheReturnStation`이 바로 이 경우(시작점 127.0403, 투영점 127.0400)를 검증합니다. 0으로 제한하자 이 테스트와 `keepsTheProvidersPathsIfTheAdditionalReturnApproachIsUnavailable`이 실패해 변경을 되돌렸습니다.

### 11. ORS 엔진에서 HTTP 400을 모두 '경로 없음'으로 처리함

- 위치: `routing/OpenRouteServiceClient.java`
- 원인: 400·404를 모두 `NO_ROAD_ROUTE`(사용자에게 404 `ROUTE_NOT_FOUND`)로 바꿨고, 401은 일반 502였습니다.
- 수정: 응답 본문의 ORS 오류 코드만 읽어 2004(거리 한도 초과), 2009(경로 없음), 2010·6010(주변에 경로 가능한 지점 없음)만 '경로 없음'으로 처리합니다. 그 밖의 4xx는 `ROUTING_UNAVAILABLE`(502), 401은 `ROUTING_AUTH`입니다. 코드 없는 404는 기존처럼 '경로 없음'입니다. 본문은 코드 판단에만 쓰고 응답·로그로 내보내지 않습니다.

## 문제없음을 확인한 부분

- 레이트 리밋 IP 식별: 배포 스크립트가 `SERVER_FORWARD_HEADERS_STRATEGY=native`를 강제하고 nginx가 `X-Forwarded-For`를 `$remote_addr`로 덮어써, 사용자별로 구분됩니다.
- 실시간 Redis 버전 교체 경쟁: 명세상 버전 Hash의 TTL이 900초라 포인터를 읽은 직후 Hash가 사라지지 않습니다.
- 백엔드의 서울 API 수집이 아직 켜져 있는 것은 문서에 보류(R1)로 기록된 의도된 상태입니다.

## 추가한 테스트

| 대상 | 테스트 |
| --- | --- |
| 1 | `InventoryDayCacheTest.cacheMissOnAVirtualThreadDoesNotPinItsCarrier`: JFR `jdk.VirtualThreadPinned` 이벤트가 없는지 확인 |
| 2 | `InventoryPreloaderTest.recentWindowIsRecopiedPeriodicallyForLateRows`, `InventoryDayCacheTest.yesterdayCanReceiveLateRowsSoItIsCachedBriefly` |
| 3·5 | `SeoulBikeRealtimeClientTest.invalidRowsAreSkippedAndTheRestIsKept`, `mostlyInvalidRowsStillFailTheRefresh`, `RealtimeSnapshotValuesTest` |
| 4 | `SeoulBikeRealtimeClientTest.emptyPageAfterAFullPageEndsTheListInsteadOfFailing`, `noDataOnTheFirstPageIsStillAnError` |
| 6 | `PlaceSearchControllerTest.failingAddressFallbackKeepsTheEmptyAnswer` |
| 7 | `KakaoPlaceSearchProviderTest.oneUnreadableDocumentIsSkippedNotTheWholeAnswer` |
| 8 | `ApiContractTest.missingAndMalformedParameters`(좌표 누락 batch → 400) |
| 9 | `ResilientCallerTest.bulkheadSlotIsHeldUntilTheCancelledExchangeEnds` |
| 11 | `OpenRouteServiceClientTest.onlyNoRouteErrorCodesBecomeNoRoadRoute` |

## 검증

| 대상 | 명령 | 결과 |
| --- | --- | --- |
| 백엔드 전체 테스트 | `./gradlew :backend:test` | 710개 중 709개 통과, 1개 건너뜀(기존), 실패 0 |
| 실행 jar | `./gradlew :backend:bootJar` | 성공 |
| 수정 전 코드로 새 테스트 실행 | 재고 캐시·서울 수집·`ResilientCaller` 구현만 `develop` 버전으로 되돌려 실행 | 새 테스트 5개 모두 실패(수정이 없으면 잡히는 것을 확인) |

- 검토 환경에서는 Maven Central이 프록시를 거쳐 `429 Too Many Requests`를 반복해, 다운로드를 한 번에 하나씩 하도록 줄여(`--max-workers=1`) 의존성을 받았습니다.
- 처음 전체 실행에서 4개가 실패했습니다. 원인은 10번 오탐(기존 테스트 2개), ORS 동작 변경을 반영하지 않은 기존 테스트 1개(`EngineBicycleRouteProviderTest`: 빈 본문 400 대신 경로 없음 코드 2010으로 갱신), 새 테스트의 Mockito 재스텁 오류 1개였고 모두 고쳤습니다.

## 미검증·후속

- 1번 효과는 단위 테스트(JFR 고정 이벤트)로만 확인했습니다. 운영 부하에서의 지연 개선은 측정하지 않았습니다.
- 2번은 최근 3시간보다 더 늦게 들어오는 기록은 여전히 다시 복사하지 않습니다. 수집기에 기록 시각 컬럼이 생기면 그 기준으로 바꾸는 것이 정확합니다.
- 3~5번은 실제 서울 API 응답으로 재현하지 않았습니다. 테스트는 고정 응답 기준입니다.
- 11번 ORS 오류 코드는 공개 문서 기준이며, 실제 ORS 응답으로는 확인하지 않았습니다(기본 엔진은 카카오).
