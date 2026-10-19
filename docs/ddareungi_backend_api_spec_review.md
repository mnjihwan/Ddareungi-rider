# API 명세서 검토 결과

검토일: 2026-09-16  
기준: 사용자가 제공한 `ddareungi_backend_api_spec_production_v4.md` 자체의 설계·API 계약. 현재 프로젝트 코드와의 일치 여부는 수정 기준으로 사용하지 않았다.  
결과: 같은 폴더의 [v5 수정본](ddareungi_backend_api_spec_production_v5.md). 다운로드 폴더의 원본과 애플리케이션 코드는 변경하지 않았다.

## 주요 발견과 수정

| 우선순위 | 원문의 문제 | 수정 내용 / 위치 |
|---|---|---|
| 높음 | FRESH의 시간 기준, STALE/미상 처리, 수집 시각의 의미가 없음 | 11.0절에서 최신성·nullable·0대와 미상 구별 정의. 원천 관측 시각과 수집 시각 구분 |
| 높음 | 짧은 Redis TTL과 오래된 캐시 제공 정책이 충돌할 수 있음 | 21~22장에서 신선도·제공 상한·물리 TTL 분리. 전체 수집 성공과 부분 성공 구분 |
| 높음 | 자전거 수 정렬·onlyAvailable 필터를 limit 전후 언제 적용할지 불명확 | 11.1절에서 반경 후보 결합 → 필터 → 정렬 → limit 순서와 동률 정렬 정의 |
| 높음 | Batch에서 존재하지 않는 ID와 실시간 미상 처리 없음 | 11.3절에서 직렬화, 중복, 순서, 부분 결과, notFoundStationIds 정의 |
| 높음 | 경로 옵션 범위·기본값·maxCandidates 의미·거리 제한 단위 없음 | 13장에서 옵션 표, 두 도보 구간 합계 제한, 반환 수와 탐색 예산 분리 |
| 높음 | 자전거 0대/미상 대여소를 포함한 경로와 실제 이용 가능성 구별 부족 | 대여/반납 가용성 상태와 최신성 추가. 반납 슬롯 보장 불가 원칙 유지 |
| 높음 | 시간대별 예측 API의 응답이 없고 시간 범위·confidence 기준 미정 | 14장에서 timeline 예시·시간 규칙·enum 추가. 모델 검증 전 확정 불가 항목 명시 |
| 중간 | 모바일 버전 선택 입력인데 forceUpdate를 확정 Boolean으로 반환 | 6장에서 모바일 버전 필수화, 숫자 단위 비교, web 동작, buildNumber 역할 정의 |
| 중간 | 장소 검색 결과 없음에 404를 사용하고 입력 제한이 빠짐 | 빈 목록 200으로 일관화. 좌표 쌍, 검색어, limit, nullable 규칙 보완 |
| 중간 | 공통 응답과 예시가 다르고 상세/Batch에 최신성 필드가 빠짐 | client-config의 meta, Station의 age, Route/Prediction의 최신성 필드 보완 |
| 중간 | `Deprecation: true`는 현재 표준 형식과 다름 | 32장에서 RFC 9745의 `@Unix초` 형식으로 수정 |
| 중간 | 복합 UNIQUE와 동일한 컬럼 인덱스 중복 | 21장에서 중복 인덱스 제거, Raw와 Canonical 충돌 처리 구분 |
| 중간 | 모든 공유 저장소를 Readiness에 넣으면 장애 격리 목표와 충돌 가능 | 16장에서 영향 기반 의존성 선정, probe 활성화·응답 형식 설명 |
| 중간 | SHA Tag라는 이름만으로 불변성/무중단 배포를 보장하는 표현 | 34~35장에서 digest/태그 불변 설정, 포트 전환 및 DB 호환 조건 추가 |
| 낮음 | 장과 하위 절 번호가 어긋남 | 8·11·12·13·14·21·39장의 번호 정정, 어색한 문장 교정 |

## 기존 설계로 유지한 내용

Java 21/Spring Boot 4.1.0, `/api/v1`, 비회원 핵심 기능, 원천 필드명 유지, MySQL·Elasticsearch·Redis 역할 분리, Provider 추상화, Global Model, GitHub Actions·ECR·EC2 및 향후 Kubernetes 방향은 유지했다. 버전 번호나 기술 선택을 현재 코드에 맞춰 바꾸지 않았다.

## 새로 제안한 값과 확정이 필요한 사항

120초 FRESH / 600초 STALE 제공 상한 / 900초 Redis TTL, 경로 옵션 제한, 검색어·결과 수 제한, 외부 호출 deadline, 예측 정시 단위·수량 구간은 원문에 없던 **설계 제안**이다. 운영에서 이미 검증된 값으로 취급하면 안 된다. 수정본에 제안값으로 표시했다.

Provider 선정과 지원 지역, 모델 최대 예측 시간·confidence 산정, Rate Limit 수치, 원천 데이터 충돌 우선순위는 문서만으로 사실을 확정할 수 없다. 수정본 41장에 결정 조건을 정리했다. 특히 최대 예측 시간을 임의로 24시간 등으로 확정하지 않았다.

## 외부 근거와 확인 한계

- [서울시 실시간 대여정보](https://data.seoul.go.kr/dataList/OA-15493/A/1/datasetView.do): 1회 최대 1,000건과 제공 필드 안내를 확인했다. 인증키로 실제 호출한 것은 아니므로 선택 stationId 인자와 응답별 종료 조건은 추가 확인이 필요하다.
- [시간대별 운영현황](https://data.seoul.go.kr/dataList/OA-20447/A/1/datasetView.do): 최근 7일, 시간 단위 안내를 확인했다.
- [장기 대여가능 수량](https://data.seoul.go.kr/dataList/OA-22382/F/1/datasetView.do): 1시간 단위 데이터와 분기 파일 목록을 확인했다. 실제 파일 헤더는 별도 검증 대상이다.
- [대여/반납 흐름 후보 OA-21229](https://data.seoul.go.kr/dataList/OA-21229/F/1/datasetView.do): 이번 조회에서 본문을 확보하지 못했다. 이를 서비스 종료나 잘못된 데이터셋의 증거로 판단하지 않고, 원문의 5분 단위/컬럼을 확인 대기로 표시했다.
- [RFC 9745](https://www.rfc-editor.org/rfc/rfc9745.html): Deprecation 헤더 형식을 확인했다.
- [Google Polyline 문서](https://developers.google.com/maps/documentation/utilities/polylinealgorithm): v5에서 사용할 경로 인코딩 형식의 근거다.
- [Spring Boot Actuator 문서](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html): probe 설정과 공유 의존성 포함 시 영향 검토의 근거다.

## 문서 검증

수정본의 JSON 코드 블록 14개가 모두 파싱되는지, 코드 블록 구분자가 균형인지, 하위 절 번호가 상위 장과 일치하는지 확인했다. 경로 예시의 거리·시간 합계와 Batch 부분 결과 예시도 점검했다. 실제 API 호출, Provider 연결, 모델 성능 또는 배포 검증은 수행하지 않았다.
