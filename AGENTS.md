# 따릉라이더 개발 지침

이 지침은 저장소 전반에 적용한다. 모바일 앱 작업은 아래 순서로 진행한다.

## 프로젝트 구성과 작업 범위

- `Ddareungi-rider/web/`은 PC와 모바일 브라우저에서 사용하는 React·TypeScript·Vite 웹 클라이언트다. 웹의 기존 사용자 흐름과 API 호환성을 유지한다.
- `Ddareungi-rider/backend/`는 Java 21·Spring Boot API다. 앱과 웹은 대여소 조회, 장소 검색, 경로 계산에 동일한 `/api/v1`을 사용한다. 현재 API 계약은 백엔드 컨트롤러와 `web/src/lib/api.ts`, `web/src/lib/types.ts`를 기준으로 확인한다.
- `Ddareungi-rider/mobile/`은 Expo PoC다. 강남역 고정 좌표로 주변 대여소를 읽는 목록과 임시 지도 화면이 있다. Expo·React Native 의존성의 호환성을 확인한 뒤 이 디렉터리에서 앱 개발을 시작한다.
- `bikeseoul_data/`는 별도 Git 서브모듈이며 `ai/`는 오프라인 실험 코드다. 모바일 앱에서 필요한 데이터는 라이더 API를 통해 가져온다.
- 기존 작업 트리의 변경을 보존한다.

## Git 작업 규칙

이 저장소는 Git Flow와 Conventional Commits를 사용한다. 기존 개발 순서와 저장소별 반영 방법은 [프로젝트 Git 작업 규칙](docs/git-workflow.md)을 참고한다.

### Git Flow

| 브랜치 | 용도와 반영 방식 |
| --- | --- |
| `main` | 배포 가능한 릴리스 이력을 관리한다. |
| `develop` | 다음 릴리스에 포함할 변경을 통합한다. |
| `feature/<topic>` | 새 기능·개선·일반 버그 수정은 `develop`에서 분기하고, 검증 후 `develop`에 `--no-ff`로 병합한다. |
| `release/<version>` | `develop`에서 분기해 릴리스를 준비하고 검증한다. 완료 후 `main`과 `develop`에 병합하고 `main`의 릴리스 커밋에 버전 태그를 붙인다. |
| `hotfix/<topic>` | 배포 버전의 긴급 수정은 `main`에서 분기하고, 검증 후 `main`과 `develop`에 반영한다. |

병합 전 원격 브랜치 상태와 관련 테스트 결과를 확인한다. 공유 브랜치의 이력을 보존하고 병합으로 변경을 반영한다.

### 커밋 컨벤션

커밋 제목은 `<type>(<scope>): <description>` 형식을 사용한다. `scope`는 선택 사항이며 생략할 때는 `<type>: <description>`으로 쓴다. 설명은 한국어로 작성하고 변경 목적과 결과를 드러낸다. 하위 호환성이 깨지는 변경은 `feat(api)!: …`처럼 제목에 `!`를 붙이거나 본문에 `BREAKING CHANGE:`를 사용해 표시한다.

| 타입 | 용도 |
| --- | --- |
| `feat` | 사용자 기능 추가 |
| `fix` | 오류 수정 |
| `refactor` | 기존 동작을 유지하는 구조 변경 |
| `perf` | 성능 개선 |
| `test` | 테스트 추가·변경 |
| `docs` | 문서 변경 |
| `build` | 의존성·빌드 설정 변경 |
| `ci` | CI/CD 설정 변경 |
| `chore` | 기타 유지보수 |
| `style` | 동작에 영향이 없는 코드 형식 변경 |
| `revert` | 이전 변경 되돌리기 |

모바일 작업의 커밋 제목 예시

- `build(mobile): Expo 개발 빌드 설정 추가`
- `feat(map): 앱 지도에 대여소 마커 표시`
- `fix(route): 목적지 변경 시 이전 경로선 제거`
- `docs(mobile): 안드로이드 실기기 검증 절차 추가`

### 커밋 단위와 서브모듈

- 선행 변경을 먼저 반영하고, 각 커밋은 그 시점의 코드로 빌드와 검토가 가능한 단위로 나눈다. 기능 검증에 필요한 테스트는 구현과 함께 묶고, 독립적인 테스트 작업에는 `test`를 사용한다. 문서·빌드 변경은 독립적으로 검토할 수 있는 단위로 나눈다.
- `bikeseoul_data/` 변경은 수집기 저장소에서 별도로 커밋한다. 수집기 변경을 해당 원격에 먼저 반영한 뒤 통합 저장소에서 서브모듈 참조를 갱신한다. 체크아웃한 수집기의 작업 규칙은 `bikeseoul_data/GIT_WORKFLOW.md`에서 확인한다.
- 비밀키, 실제 `.env`, 가상환경, 모델·원본 데이터, 로컬 테스트 산출물은 커밋에서 제외한다. 커밋 전 스테이징한 파일과 변경 내용을 확인한다.

## 모바일 앱 개발 순서

모바일 작업을 시작할 때 [루트 README](README.md), [모바일 앱 실행 안내](Ddareungi-rider/mobile/README.md), [길찾기 계약](docs/routing.md), [Git 작업 규칙](docs/git-workflow.md)을 확인한다. 전체 작업을 맡은 경우 아래 순서로 구현·검증을 이어간다. 단계마다 변경 내용과 실제 검증 결과를 남긴다. 기기·키·계정이 필요한 시나리오는 막힌 원인과 재현 절차를 기록하고, 그 결과에 의존하지 않는 후속 작업을 계속한다.

### 1. 안드로이드에서 지도와 API 연결 검증

1. `Ddareungi-rider/mobile/`에서 `npx expo install --check`와 `npx expo-doctor`로 기존 Expo 설정과 의존성을 점검한다. 문제가 있으면 관련 패키지와 잠금 파일을 함께 정리한다.
2. 휴대폰에서 접속할 수 있는 개발 API 주소를 `EXPO_PUBLIC_API_BASE_URL`에 설정한다. 기존 PoC에서는 이 값이 서버 주소이고 요청 경로에 `/api/v1`이 포함된다. 기본값인 `localhost:8080`은 실기기에서 휴대폰 자신을 가리킨다. 테스트 배포와 운영에는 HTTPS를 사용한다.
3. 카카오 Android·iOS 지도 SDK를 지원하는 React Native 라이브러리를 검토한다. 두 플랫폼에서 지도 표시, 마커 클릭, 지도 경계 변경, 경로선 표시를 확인할 수 있는지 먼저 본다. 네이티브 SDK는 Expo 개발 빌드로 검증하고, 카카오 Developers의 네이티브 앱 키에 해당 빌드의 Android 패키지명·키 해시와 iOS 번들 ID를 등록한다. [카카오맵 SDK](https://developers.kakao.com/docs/ko/kakaomap/common) · [Expo 개발 빌드](https://docs.expo.dev/develop/development-builds/faq/)
4. 안드로이드 실기기에서 지도 타일을 띄우고, API에서 내려준 대여소 마커를 눌러 목록 또는 상세와 연결되는지 확인한다. 실제 `POST /api/v1/routes/search` 응답의 `data.recommended.segments`에서 `encodedPolyline`을 좌표로 변환해 도보 → 자전거 → 도보 세 구간을 그린다. 샘플 모드는 대여소 조회에 사용하고, 실제 경로 검증에는 카카오 키와 실시간 수량이 연결된 환경을 사용한다.

### 2. 따릉이 이용 흐름 구현

1. `expo-location`으로 앱 사용 중 위치 권한을 요청한다. 권한 거부, 위치 서비스 꺼짐, 위치 조회 실패 시 장소 검색과 지도에서 출발지 선택을 제공한다. [Expo 위치 권한](https://docs.expo.dev/versions/latest/sdk/location/)
2. 현재 위치 주변 목록은 `GET /api/v1/stations/nearby`의 `radius`·`limit`과 `data.items`를 사용하고, 지도 영역은 `GET /api/v1/stations/viewport`를 활용한다. `parkingBikeTotCnt`가 `0`이면 0대로, `null`이면 수량 확인 불가로 표시한다. 갱신 시각과 최신성은 `realtimeUpdatedAt`과 `realtimeStatus`를 따른다.
3. 대여소·장소 검색에는 `GET /api/v1/stations/search`와 `GET /api/v1/places/search`를 사용한다. 목적지를 선택한 뒤 `POST /api/v1/routes/search`에서 받은 추천 경로의 대여·반납 대여소, 세 구간의 경로선, 거리·예상 시간을 표시한다. 출발지 또는 목적지를 바꾸면 이전 경로 표시를 지운다. 경로 계산과 오류 처리 기준은 [길찾기 안내](docs/routing.md)를 따른다.
4. 웹과 앱에 함께 필요한 API 타입과 검색 가공 로직부터 공유한다. 브라우저의 `window.kakao`, DOM, CSS에 의존하는 지도·화면은 앱용으로 구현한다. 핵심 이용 흐름을 확인한 뒤 즐겨찾기와 최근 검색을 앱 저장소에 연결하고 재실행 후 유지되는지 확인한다.

### 3. Android와 iOS 실기기 검증

1. 지도 라이브러리 선택 시 iOS 빌드 요건을 확인하고, 환경이 준비되면 지도·마커·경로선을 조기에 시험한다. Windows에서는 EAS 클라우드 빌드를 사용할 수 있다. [iOS 실기기 개발 빌드](https://docs.expo.dev/get-started/set-up-your-environment/?device=physical&mode=development-build&platform=ios)
2. Android·iOS 실기기에서 위치 허용·거부, 지도 이동 후 대여소 갱신, 장소 검색, 세 구간 경로, 출발지·목적지 변경, API 실패 후 재시도, 저장 항목의 재실행 후 유지를 확인한다. 기기·OS, 빌드, API 환경, 실제 확인한 시나리오와 미검증 항목을 남긴다. 고정 응답·에뮬레이터 결과와 실기기·실제 API 결과를 구분한다.

### 4. 내부 테스트 배포와 스토어 제출 준비

1. 실제 빌드에 쓰는 Android 패키지명·iOS 번들 ID, 버전과 빌드 번호, 환경별 HTTPS API 주소, 서명 준비 상태를 확인한다. 앱 이름·아이콘·위치 권한 문구와 개인정보 처리 안내가 구현과 일치하는지 검토한다.
2. 승인된 테스트 환경과 계정으로 배포용 빌드를 준비하고, 내부 배포 권한이 있으면 배포본을 설치해 핵심 흐름을 재확인한다. 클라우드 빌드, 내부 배포, 스토어 제출·공개는 사용자에게 이미 승인받은 범위에서 진행한다. [Expo 내부 배포](https://docs.expo.dev/build/internal-distribution/)
3. 앱을 실행 대상으로 전환하는 변경에는 README의 PoC 상태를 갱신하고 설치·실행·환경변수·실기기 검증 방법을 기록한다. 배포 준비, 배포본 설치 검증, 스토어 제출·공개 상태를 각각 기록한다.

## 설정과 검증

- `EXPO_PUBLIC_` 값은 앱 번들에 포함된다. 앱의 공개 설정에는 API 주소와 SDK에 필요한 공개 설정만 둔다. 카카오 REST 키와 서울시 API 키는 서버 환경에서 관리하고, 예제 환경 파일에는 변수명과 예시 값만 넣는다. [Expo 환경변수](https://docs.expo.dev/guides/environment-variables/)
- 샘플 실행과 중지는 저장소 루트에서 아래 명령을 각각 사용한다. 기본 주소는 웹 `http://localhost:5173`, 백엔드 `http://localhost:8080`이다. 실제 데이터 실행은 [루트 README](README.md#실제-데이터-실행)를 참고한다.

  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Mode Sample
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-local.ps1
  ```

- 앱을 수정하면 루트에서 `npm --prefix Ddareungi-rider/mobile run typecheck`를 실행하고, 지도·위치·네이티브 설정 변경은 개발 빌드에서 확인한다.
- 웹 또는 공용 코드를 수정하면 루트에서 `npm --prefix Ddareungi-rider/web test`와 `npm --prefix Ddareungi-rider/web run build`를 실행한다.
- 백엔드를 수정하면 `Ddareungi-rider/`에서 `.\gradlew.bat :backend:test`를 실행한다.
