# 국내 도보·자전거 경로 API 조사 — 2026-10-10

현재 서비스의 목적은 출발지 → 대여 대여소 → 반납 대여소 → 목적지를 도보 → 자전거 → 도보로 연결하고, 경로와 안내를 서비스 안에서 표시하는 것이다. 현재 네이버 지도와 장소 검색, 서울시 대여소 데이터를 유지하는 조건으로 조사했다.

**추천은 카카오맵 공개 경로 REST API를 우선 검증하고, ODsay를 대안으로 두는 것이다.** 카카오모빌리티의 제휴용 API와 카카오디벨로퍼스의 공개 API는 구분해야 한다. 공개 도보·자전거 API는 2026-07-21 추가됐으며, 별도 제휴 심사 없이 카카오맵 API 활성화로 이용할 수 있다. [공식 신규 API 공지](https://devtalk.kakao.com/t/api-notice-on-new-kakao-map-api-features-and-free-quota-policy/150222)

## 확인 범위

공식 문서, 가격표, 2026년 공지, 담당자 답변 및 현재 프로젝트 코드를 확인했다. 신규 공급자의 인증키를 발급하거나 인증이 필요한 경로를 실제 호출하지는 않았다. 따라서 문서상 제공·운영 여부와 연동 가능성에 대한 조사이며, 서울의 실제 경로 품질·우리 계정의 인증 성공을 검증한 결과는 아니다. 이번 변경은 조사 문서 추가이며 실행 코드는 기존 ORS 연동 상태다.

## 후보 비교

| 후보 | 도보 | 자전거 | 이용 시작 | 이번 서비스 판단 |
| --- | --- | --- | --- | --- |
| 카카오맵 공개 REST API | 지원 | 지원 | 앱 생성 및 카카오맵 API 활성화 | 우선 추천. 두 수단, 한국어 안내, 자전거도로 우선 옵션, 공개 요금 제공 |
| ODsay LAB | 지원 | 지원 | 애플리케이션 등록 및 키 발급 | 대안. 다른 지도와 조합 가능하다고 명시 |
| 카카오모빌리티 Partnership API | 지원 | 지원 | 제휴 계약·권한 승인 | 별도 사업 제휴가 필요한 경우 검토 |
| TMAP Open API | 공개 보행자 API 확인 | 현재 공개 문서·약관에서 확인되지 않음 | SK Open API 앱·상품 등록 | 도보 공급자로는 후보지만 전체 서비스에 단독 적용하기 어려움 |

근거: [카카오맵 REST API](https://developers.kakao.com/docs/ko/kakaomap/rest-api#route), [ODsay API Reference](https://lab.odsay.com/guide/releaseReference), [카카오모빌리티 제휴용 자전거 시작하기](https://developers.kakaomobility.com/affiliate/bicycle/start), [TMAP 보행자 API](https://tmapapi.tmapmobility.com/main.html#webservice/docs/tmapRoutePedestrianDoc), [TMAP API 약관·기능 목록](https://tmapapi.tmapmobility.com/terms.html).

### 국내 사용 사례를 해석하는 범위

TMAP은 공식 API 소개에 배달의민족, CU, 이마트, 롯데마트의 도입 사례를 공개한다. 다만 장소 검색·물류·자동차 경로 사례이므로 도보나 자전거 API 도입 사례로 해석하면 안 된다. [TMAP API 적용 서비스](https://tmapapi.tmapmobility.com/)

ODsay 운영사 아로정보기술은 인터파크 트리플의 ODsay 다국어 API 도입과 네이버 대중교통 정보·길찾기 제휴 이력을 공개한다. 이것도 도보·자전거 엔드포인트의 이용량을 입증하지는 않는다. [아로정보기술 공식 소개·소식](https://www.arointech.co.kr/)

카카오맵 자체는 국내 사용 기반이 큰 지도 서비스지만 공개 도보·자전거 API는 2026년 7월에 추가된 기능이다. 해당 API의 국내 도입 건수·시장점유율은 이번 조사에서 확인하지 못했다. 국내에서 널리 쓰이는 지도 서비스라는 점과 새 경로 API의 도입 빈도는 구분해야 한다. [카카오 공식 서비스 소개](https://www.kakaocorp.com/page/detail/10935), [2026년 신규 API 공지](https://devtalk.kakao.com/t/api-notice-on-new-kakao-map-api-features-and-free-quota-policy/150222)

기존 openrouteservice를 국내에서 많이 사용되는 API로 우선 추천할 공개 근거는 확보하지 못했다. 국내 공급자와 비교해 서울 경로 품질을 확인하기 전에는 더 우수하다고 단정할 수 없다.

## 1. 카카오맵 공개 API

### 인증과 요청

- 도보: `GET https://dapi.kakao.com/v2/routing/walk`
- 자전거: `GET https://dapi.kakao.com/v2/routing/bicycle`
- 인증 헤더: `Authorization: KakaoAK ${REST_API_KEY}`
- 출발·도착: `start_x`, `start_y`, `end_x`, `end_y`. 입력·출력은 `WGS84`로 지정한다.
- 도보 옵션: `BROAD_FIRST`, `SHORTEST`, `ACCESSIBLE`.
- 자전거 옵션: `BIKE_ONLY`(자전거도로 우선), `SHORTEST`, `ACCESSIBLE`.

응답의 `status`가 `OK`일 때 `route.properties.totalDistance`(m), `totalTime`(s), `route.legs[].steps[].path.points`의 좌표 쌍 `[경도, 위도]`, 단계별 `properties.guidance`를 사용한다. `landingUrl`로 이동할 필요 없이 경로 좌표와 안내를 직접 표시할 수 있다. [공식 요청·응답 문서](https://developers.kakao.com/docs/ko/kakaomap/rest-api#route)

`BIKE_ONLY`는 자전거도로 우선 옵션이며 전 구간 전용도로를 보장한다는 의미로 표시하면 안 된다. `ACCESSIBLE` 도보 옵션 역시 계단 없는 길을 보장하지 않는다고 담당자가 설명한다. [2026-10-08 담당자 답변](https://devtalk.kakao.com/t/api-accessible/152018)

### 요금과 지도 조합

개발자 계정에서 첫 번째로 카카오맵 API를 활성화한 앱은 도보·자전거 각각 일 1,000건 무료다. 두 번째 이후 활성화 앱 또는 무료량 초과 시 유료 설정과 비즈월렛이 필요하며 공식 표기 단가는 각각 건당 10원이다. 이미 대여·반납 대여소가 정해진 세 구간을 각 한 번 조회하는 경우 유료 호출 단가의 합은 30원이다. 후보 비교·재검색은 추가 호출이므로 이 금액을 현재 자동 추천 검색의 비용이라고 보면 안 된다. [현재 무료 쿼터·단가](https://developers.kakao.com/docs/ko/getting-started/quota)

담당자는 카카오맵 REST API를 다른 지도 API와 조합하는 것을 제한하지 않는다고 답변했다. 따라서 네이버 지도와 조합하는 근거가 있다. [2026-08-18 지도 조합 답변](https://devtalk.kakao.com/t/local-api/151190/2)

실시간으로 조회해 화면에 일시적으로 표시하는 구조는 허용되지만, 응답 및 가공 결과를 저장해 반복 사용하는 구조는 허용되지 않는다고 안내한다. **Redis·DB·브라우저 저장소·공유 메모리에 경로/이동 시간을 캐싱해 다른 요청에 재사용하는 설계는 피해야 한다.** [경로 표시·저장 범위 답변](https://devtalk.kakao.com/t/local-api/151263/2), [단기 이동 시간 캐싱 불가 답변](https://devtalk.kakao.com/t/api-totaltime-caching/151653/2)

### 사용자가 준비할 항목

1. [카카오디벨로퍼스](https://developers.kakao.com/)에서 서비스용 앱을 생성한다.
2. 해당 앱의 **카카오맵 → 사용 설정 → ON**을 적용한다.
3. **앱 → 플랫폼 키**에서 REST API 키를 확인한다. 이번 서버 경로 조회에 JavaScript 키나 어드민 키를 사용하지 않는다.
4. 첫 번째 활성화 앱의 무료 쿼터 적용 여부를 확인한다. 유료 이용이 필요하면 비즈월렛을 연결한다.
5. 연동 구현 시 프로젝트 최상위 `.env`의 서버 전용 `KAKAO_REST_API_KEY`로 전달한다. 이 변수는 제안이며 아직 실행 코드에 추가되지 않았다.

이용 절차 근거: [카카오맵 사용 방법](https://developers.kakao.com/docs/ko/kakaomap/common), [플랫폼 키·호출 허용 IP 설정](https://developers.kakao.com/docs/ko/app-setting/app).

## 2. ODsay 대안

도보 `searchWalkPathV2`, 자전거 `searchBikePathV2`를 제공한다. `loc`에 출발·도착 경위도를 전달하며 여러 좌표 쌍도 받을 수 있다. 성공한 `result.path[]`의 `recommend.routes[].coordinate[]`에 있는 `{x, y}`를 경도·위도로 변환하고, `recommend.summary`의 거리·시간과 `guides`의 안내를 사용한다. 도보는 `opt=reco`를 지정하는 방식으로 응답 종류를 고정할 수 있다. [ODsay API Reference](https://lab.odsay.com/guide/releaseReference)

Basic은 개인·학생·5인 이하 스타트업 대상으로 일 30건 무료다. Flex의 도보·자전거 API는 각각 25원/건(VAT 별도)이며, 대여소가 정해진 세 구간 3건은 75원(VAT 별도)이다. 여러 좌표 쌍을 한 요청에 담았을 때의 과금 집계는 공개 가격표만으로 확정하지 않았다. [공식 요금표](https://lab.odsay.com/contact/contact)

소개 페이지는 경위도 좌표로 원하는 지도에 조합할 수 있다고 안내하고 네이버 지도 예시도 제시한다. 서버 플랫폼으로 등록하면 호출 서버의 공인 IP 설정을 확인해야 한다. 로컬 `localhost` 주소와 외부 API가 보는 공인 IP는 다르다. 서비스에는 ODsay 출처도 표시해야 한다. [지도 조합 소개](https://lab.odsay.com/introduce/intro), [IP 인증·상표 표기 FAQ](https://lab.odsay.com/community/faq)

## 3. 카카오모빌리티·TMAP의 적용 범위

카카오모빌리티의 `/affiliate/walking/v1/directions`, `/affiliate/bicycle/v1/directions`는 공개 카카오맵 API와 다른 상품이다. 제휴 계약과 승인된 REST 키 권한이 필요하다. 경로는 `routes[].sections[].roads[].vertexes`의 경도·위도 쌍에서 얻을 수 있으나, 단가와 타사 지도 표시 조건은 제휴 계약을 기준으로 확인해야 한다. [제휴 시작하기](https://developers.kakaomobility.com/affiliate/bicycle/start), [자전거 응답 문서](https://developers.kakaomobility.com/affiliate/bicycle/directions), [도보 응답 문서](https://developers.kakaomobility.com/affiliate/walking/directions)

TMAP 공개 보행자는 `POST https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1`에 `appKey` 헤더를 사용한다. `reqCoordType`·`resCoordType`을 `WGS84GEO`로 지정하고, GeoJSON의 `LineString` 좌표를 표시할 수 있다. 현재 공개 문서에는 자전거 경로 상품을 확인하지 못했으므로 자동차·보행자 응답을 자전거 경로로 대신 쓰면 안 된다. [TMAP 보행자 문서](https://tmapapi.tmapmobility.com/main.html#webservice/docs/tmapRoutePedestrianDoc)

## 현재 프로젝트에 연동하는 방법

```text
네이버 장소 검색 / 현재 위치 선택
                 ↓
POST /api/v1/routes/plan — Spring 백엔드
                 ↓
서울시 재고로 대여·반납 대여소 후보 확인
                 ↓
카카오맵 또는 ODsay에 도보 / 자전거 / 도보 조회
                 ↓
공통 응답: legs[3], path, steps, 거리(m), 시간(s), 출처
                 ↓
React StationMap → 네이버 Polyline과 마커로 표시
```

현재 `RouteProvider`가 경로 공급자를 추상화하고, `StationMap.tsx`가 구간별 `path`를 네이버 `Polyline`으로 그린다. 공급자 응답을 기존 `Leg` 형태로 변환하면 지도 표시 코드를 재사용할 수 있다. 새 공급자의 서버 클라이언트와 설정 선택을 추가하고, ORS에 고정된 출처와 후보 비교 방식을 수정하는 작업이 필요하다.

네이버는 위도·경도를 받으므로 외부 좌표 `[경도, 위도]`를 `new naver.maps.LatLng(위도, 경도)`로 전달한다. 반환된 경로의 모든 점을 순서대로 표시하고, 대여소 두 점을 직선으로 연결하는 대체 경로를 만들지 않는다. 도보와 자전거를 별도 선으로 그리면 색상·두께·점선을 다르게 적용할 수 있다. 기존 코드가 이미 이 방식을 사용한다. [네이버 Polyline 공식 문서](https://navermaps.github.io/maps.js.ncp/docs/naver.maps.Polyline.html)

### 자동 대여소 추천의 호출 수

현재 `PlanBikeRoute`는 출발 후보 최대 3개, 도착 후보 최대 3개를 비교한다. ORS는 시간 행렬 3회와 최종 경로 3회를 호출한다. 공개 카카오맵 경로 API에는 확인한 문서상 동일한 행렬 엔드포인트가 없다. 동일한 비교를 개별 조회로 옮기면 최대 **도보 6개 + 자전거 9개 = 15개 좌표 쌍**을 조회해야 한다. 기존 인터페이스에 단순 대입하고 선택한 세 구간을 다시 요청하면 최대 18회가 된다.

카카오 연동에서는 한 번의 계획 요청에서 받은 경로의 시간과 형상을 함께 평가하고, 선택한 결과를 바로 화면에 전달하도록 설계를 조정하는 것이 좋다. 다른 검색에 재사용하는 캐시는 두지 않는다. 필요하면 후보 수를 줄이거나 사용자가 대여소를 지정하는 흐름으로 호출 수를 줄인다. 같은 대여소 조합·연결 불가 조합의 처리는 기존 추천 규칙을 유지한다.

### 키 입력 후 실제 검증할 내용

인증과 한도 오류 처리, 실제 도보·자전거·도보 연결, 지도 좌표 순서, 한국어 안내, 재고 변경 시 다시 검색, PC·모바일 경로 화면을 검증한다. 서울에서는 한강 진입로·교량·지하도·횡단보도·자전거도로 연결 구간을 포함해 확인해야 한다. 지도 선과 구간별 안내 표시가 우선이며, 이동 중 GPS 추적·경로 이탈 재탐색·음성 내비게이션은 별도 기능이다. AI 예측 영역은 기존대로 유지할 수 있다.
