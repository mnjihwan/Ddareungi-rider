# 따릉라이더 Expo 앱

이 디렉터리는 Android·iOS 앱 개발의 출발점인 Expo PoC입니다. 현재 `HomeScreen`은 강남역 고정 좌표에서 주변 대여소 목록을 요청합니다. 샘플 조회 전에 PoC의 `radiusMeters`와 배열 응답 처리를 현재 API의 `radius`·`limit`과 `data.items`에 맞춥니다. 목록에서 읽는 대여·거치대 수량 필드도 `parkingBikeTotCnt`·`rackTotCnt`에 맞춰 연결합니다. 지도는 임시 화면이며 카카오 네이티브 지도, 기기 위치 조회, 길찾기와 배포는 개발 단계에 포함됩니다. [전체 개발 순서](../../AGENTS.md#모바일-앱-개발-순서)

## 로컬 준비

1. 저장소 루트에서 [샘플 모드](../../README.md#샘플-실행-dockerapi-키-불필요)로 백엔드를 시작합니다. 샘플은 주변 대여소 조회에 사용할 수 있습니다. 실제 장소·경로 검증 환경은 [길찾기 안내](../../docs/routing.md#인증과-실행)를 따릅니다.
2. `Ddareungi-rider/mobile/`에 `.env`가 없으면 이 디렉터리의 `.env.example`을 복사합니다. `EXPO_PUBLIC_API_BASE_URL`에 기기에서 접근 가능한 서버 주소를 입력합니다. 요청 경로에 `/api/v1`이 이미 포함되므로 이 값에는 서버 주소까지만 적습니다.

   | 실행 환경 | API 주소 |
   | --- | --- |
   | 개발 PC에서 백엔드 응답 확인 | `http://localhost:8080` |
   | Android Studio 에뮬레이터에서 개발 PC 접속 | `http://10.0.2.2:8080` |
   | Android·iOS 실기기 | 기기에서 접속할 수 있는 개발 서버 주소. 같은 Wi-Fi의 PC 주소를 쓸 경우 서버 바인딩, 방화벽과 앱의 HTTP 허용 여부를 확인합니다. |
   | 내부 배포·운영 빌드 | 해당 환경의 HTTPS API 주소 |

   실기기의 `localhost`는 휴대폰 자신을 가리킵니다. `EXPO_PUBLIC_` 값은 앱 번들에 포함되므로 카카오 REST 키와 서울시 API 키는 백엔드 환경에 둡니다. [Android 에뮬레이터 네트워크](https://developer.android.com/studio/run/emulator-networking-address) · [Expo 환경변수](https://docs.expo.dev/guides/environment-variables/)
3. 아래 명령은 `Ddareungi-rider/mobile/`에서 실행합니다. 의존성이나 설정 문제는 출력에 따라 해결하고 잠금 파일과 함께 검증합니다.

   ```powershell
   npm ci
   npx expo install --check
   npx expo-doctor
   npm run typecheck
   npm run start
   ```

   `npm run start`는 Expo 개발 서버를 시작합니다. 네이티브 지도를 연결한 뒤에는 해당 SDK를 포함한 개발 빌드를 설치해 실행합니다. 현재 `app.json`의 Android 패키지명과 iOS 번들 ID는 `com.ddareungi.rider`입니다. 카카오 Developers에 등록할 때 실제 빌드의 식별자와 Android 서명 키 해시를 확인하세요. [Expo 개발 빌드](https://docs.expo.dev/develop/development-builds/introduction/) · [카카오맵 SDK 등록](https://developers.kakao.com/docs/ko/kakaomap/common)

## 단계별 검증

| 단계 | 확인할 결과 |
| --- | --- |
| 안드로이드 지도·API | 개발 빌드에서 지도 타일, 대여소 마커 선택, 도보 → 자전거 → 도보 경로선 표시 |
| 따릉이 이용 흐름 | 위치 권한 허용·거부, 대여소와 장소 검색, 경로 안내, 즐겨찾기·최근 검색의 재실행 후 유지 |
| 양 플랫폼 실기기 | 동일한 핵심 흐름, 지도 이동 후 대여소 갱신, 경로 변경·재시도 |
| 내부 테스트 배포 | 환경별 API 주소와 서명 확인, 설치 가능한 배포본 검증, 스토어 제출 준비 |

실행한 명령, 기기·OS, 빌드, API 환경과 결과를 기록합니다. Windows에서 iOS 실기기 빌드가 필요하면 EAS 클라우드 빌드의 계정·서명 요건과 승인된 배포 범위를 확인합니다. [iOS 실기기 빌드](https://docs.expo.dev/get-started/set-up-your-environment/?device=physical&mode=development-build&platform=ios) · [Expo 내부 배포](https://docs.expo.dev/build/internal-distribution/)
