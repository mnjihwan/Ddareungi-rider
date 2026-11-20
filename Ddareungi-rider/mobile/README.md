# 따릉라이더 모바일 앱

Android·iOS용 Expo 앱입니다. 모바일 웹의 브랜드 헤더, 지도 위 검색·조작 도구, 3단계 하단 패널을 앱에 적용했습니다. 카카오 네이티브 지도에서 주변 대여소를 찾고, 위치·장소 검색·지도에서 출발지와 목적지를 선택해 도보 → 자전거 → 도보 경로를 확인할 수 있습니다. 즐겨찾기와 최근 검색은 기기에 저장합니다. 앱과 웹은 같은 라이더 API 및 검색·길찾기 계약을 사용합니다. [전체 개발 순서](../../AGENTS.md#모바일-앱-개발-순서) · [길찾기 계약](../../docs/routing.md)

## 로컬 개발 빌드

1. 저장소 루트에서 대여소용 샘플 서버를 실행합니다. 실제 장소·경로 확인에는 [실제 데이터 환경](../../README.md#실제-데이터-실행)과 카카오 REST 키, 최신 서울시 대여 수량이 필요합니다. 실행 중인 샘플 서버는 두 번째 명령으로 종료할 수 있습니다.

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Mode Sample
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-local.ps1
   ```

2. `Ddareungi-rider/mobile/`에 `.env`가 없으면 `.env.example`을 복사합니다. `EXPO_PUBLIC_API_BASE_URL`에 해당 기기에서 도달 가능한 서버 원본 주소를 적습니다. 앱이 요청에 `/api/v1`을 붙입니다.

   | 실행 환경 | API 주소 |
   | --- | --- |
   | 개발 PC에서 응답 확인 | `http://localhost:8080` |
   | Android ARM 에뮬레이터에서 개발 PC에 접속 | `http://10.0.2.2:8080` |
   | Android 실기기 개발 빌드에서 같은 Wi-Fi의 개발 PC에 접속 | PC의 LAN 주소와 포트를 입력하고 서버 바인딩·방화벽을 확인합니다. |
   | iOS 실기기 및 내부 테스트·운영 빌드 | 기기에서 접근 가능한 환경별 HTTPS 서버 원본 주소를 사용합니다. |

   실기기의 `localhost`는 휴대폰 자신입니다. USB로 연결한 Android 실기기에서는 `adb reverse tcp:8080 tcp:8080`을 실행한 뒤 디버그 빌드에서 `http://localhost:8080`을 사용할 수 있습니다. Android HTTP 예외는 디버그 매니페스트에만 적용됩니다. iOS 개발 설정은 로컬 네트워크를 허용하고, 내부 테스트·운영에는 ATS 기본 보호를 적용합니다. [Android 에뮬레이터 네트워크](https://developer.android.com/studio/run/emulator-networking-address) · [Apple ATS 로컬 네트워크](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking)

3. `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY`에는 지도 사용이 활성화된 앱의 카카오 **네이티브 앱 키**를 입력합니다. 카카오 Developers에서 Android 패키지명과 해당 빌드를 서명한 인증서의 키 해시, iOS 번들 ID를 등록합니다. 테스트 키와 릴리스 키 해시는 실제 인증서를 각각 확인합니다. `EXPO_PUBLIC_` 값은 앱 번들에 포함됩니다. 카카오 REST 키와 서울시 API 키는 백엔드 환경에서 관리합니다. [카카오맵 SDK](https://developers.kakao.com/docs/ko/kakaomap/common) · [네이티브 플랫폼 키와 앱 등록](https://developers.kakao.com/docs/ko/app-setting/app) · [Expo 환경변수](https://docs.expo.dev/guides/environment-variables/)

4. 아래 명령은 `Ddareungi-rider/mobile/`에서 실행합니다. 카카오맵을 포함하는 개발 빌드가 필요합니다. Android는 SDK와 `adb`가 있는 PC에서 ARM 기기에 설치합니다. Expo SDK 57의 iOS 최소 버전은 16.4입니다. iOS 로컬 빌드는 Xcode 26.4 이상과 CocoaPods가 있는 Mac에서 실행합니다. `npm run start`는 기기에 개발 빌드가 설치된 뒤 Metro를 다시 시작할 때 사용합니다. `android/`와 `ios/`는 Expo가 생성하며 Git에서 제외합니다. [Expo SDK 57](https://docs.expo.dev/versions/v57.0.0/) · [Expo 개발 빌드](https://docs.expo.dev/develop/development-builds/introduction/) · [Android 카카오 지도 SDK 요구사항](https://apis.map.kakao.com/android_v2/docs/getting-started/) · [React Native Kakao 지도](https://rnkakao.mjstudio.net/en/docs/map/intro)

   ```powershell
   npm ci
   npx expo install --check
   npx expo-doctor
   npm run typecheck
   npm test
   npm run android -- --device
   npm run start
   ```

   Mac에서는 해당 플랫폼을 선택해 `npm run ios -- --device`를 실행할 수 있습니다. Windows에서 iOS 개발 빌드가 필요하면 계정·서명·기기 등록을 준비하고 승인 범위에서 EAS 빌드를 사용합니다. [iOS 실기기 개발 빌드](https://docs.expo.dev/get-started/set-up-your-environment/?device=physical&mode=development-build&platform=ios)

## 예상 대여 수량 설정

현재 운영 예측 서비스는 연결되어 있지 않습니다. 예측 API를 제공하는 환경에서는 모바일 `.env` 또는 빌드 환경에 `EXPO_PUBLIC_FORECAST_API_PATH=/stations/forecasts`를 설정하고 Metro를 재시작하거나 앱을 다시 빌드합니다. 앱은 설정된 라이더 API 주소의 `/api/v1/stations/forecasts`를 사용합니다. 경로가 비어 있으면 목록에서 대여 예정 시간을 선택해도 현재 수량을 유지하고 준비 중 안내를 표시합니다. 상세 화면의 AI 예측 패널은 경로를 설정한 환경에 표시합니다. [예측 API 계약](../../docs/forecast-integration.md)

## Android·iOS 실기기 검증

아래 시나리오를 각 플랫폼에서 확인합니다. 기록에 기기와 OS 버전, 빌드 종류와 버전, 샘플 또는 실제 API 환경, 성공·실패한 시나리오를 남깁니다.

| 시나리오 | 기기에서 확인할 결과 |
| --- | --- |
| 지도와 대여소 | 카카오 지도 타일 표시, 대여소 마커 선택 시 상세 열기, 지도 이동 후 대여소 갱신과 수동 새로고침 |
| 위치 | 허용·거부, 위치 서비스 끄기, 낮은 정확도와 조회 지연에서 장소 검색·지도 선택으로 계속 진행 |
| 위치 만료 | 2분이 지나거나 복귀할 때 오래된 위치를 갱신하도록 안내하고, 경로에 이미 선택한 좌표는 마지막 확인 위치로 유지. 위치 권한이나 서비스가 해제된 상태로 복귀하면 현재 위치 대신 장소 검색·지도 선택을 안내 |
| 검색 | 장소와 대여소 결과 선택, 한쪽 검색 실패 시 다른 결과 이용과 실패 항목 재시도 |
| 추천 경로 | 실제 환경에서 두 대여소와 도보 → 자전거 → 도보 경로선, 거리·예상 시간, 경로 대여소 수량 새로고침 |
| 경로 변경 | 출발지·목적지 변경 또는 지우기 후 이전 경로 제거, 늦은 이전 응답 무시, 실패 후 재검색 |
| 저장 | 즐겨찾기·최근 검색 추가 후 앱을 종료하고 재실행, 기존 항목 확인 및 삭제 |
| 개발용 경로 확인 | 개발 빌드 화면 하단의 **개발용 경로 확인**에서 강남역 → 역삼역의 `/routes/plan` 좌표 배열로 세 구간을 표시. 일반 길찾기는 `/routes/search` 사용 |

### 실행한 검증

기준은 `origin/develop`의 `d45b30668950f56058f1ffcd90c648057be6bf91`에서 분기한 `feature/mobile-rider-app`입니다. Windows 11, Java 21.0.12, Node 24.18.0에서 작업했습니다.

| 검증 | 실제 결과 |
| --- | --- |
| Expo 의존성과 프로젝트 | `npx expo install --check` 통과, `npx expo-doctor` 21/21. Expo 57.0.33, React Native 0.86.3, React Native Kakao Map 3.2.0 |
| 고정 응답과 타입 | `npm --prefix Ddareungi-rider/mobile run typecheck` 통과, `npm --prefix Ddareungi-rider/mobile test` 10개 통과. 0대·수량 없음·조회 시각·지도 응답 제한, 세 경로 변환과 이전 응답 차단, GPS 대체 흐름·만료 좌표와 복귀 시 권한 처리, 저장 순서를 확인 |
| 웹 호환성 | `npm --prefix Ddareungi-rider/web test` 82개와 `npm --prefix Ddareungi-rider/web run build` 통과 |
| 샘플 API | 최초 `./gradlew.bat :backend:bootJar --offline --no-daemon`에서 기존 Gradle 출력물 정리에 실패. 같은 명령에 임시 `--project-cache-dir`과 출력 경로를 바꾸는 `--init-script`를 추가해 빌드 성공. 별도 로컬 백엔드에서 주변·지도 영역·대여소 검색 HTTP 200과 샘플 레코드 3개 확인. 길찾기 상태는 `configured=false` |
| 실제 API | 같은 JAR을 별도 로컬 포트에서 테스트용 MySQL·Redis와 실제 카카오에 연결. 주변 대여소 20개와 ‘역삼역’ 검색 결과 16개를 직접 조회. 강남역 → 역삼역 `/routes/plan` HTTP 200 및 `Deprecation` 헤더, 세 좌표 배열 8·10·2개와 대여·반납 FRESH 확인. `/routes/search`에서도 WALK → BIKE → WALK, 922m·558초 확인 |
| 실제 API와 앱 코드 | Node에서 앱의 HTTP 클라이언트와 지도 표시 데이터 변환을 실행. 주변 대여소 5개와 장소 16개, 실제 경로의 좌표 8·10·2개, 눌러서 선택할 수 있도록 구성된 대여·반납 라벨을 확인 |
| Android 로컬 설정 | `npx expo prebuild --platform android --no-install`과 카카오 모듈 자동 연결 확인. `npx expo run:android --no-bundler`는 SDK 경로와 `adb`가 없어 네이티브 컴파일 전에 종료 |
| iOS 로컬 설정 | `npx expo config --type introspect --json`으로 설정과 권한을 확인하고 카카오 지도 Pod 자동 연결을 확인. `npx expo prebuild --platform ios --no-install`은 Windows에서 iOS 프로젝트 생성을 건너뛰어 종료. Xcode 빌드는 미검증 |
| 양 플랫폼 번들 | `npx expo export --platform all`로 Android·iOS Hermes JavaScript 번들 생성 |

위 표는 호스트에서 실행한 초기 검증입니다. 아래 Android 에뮬레이터 결과와 별도로 양 플랫폼 실기기에서 지도, 위치 권한, 저장 복원을 확인합니다.

단계별 검토에서 1단계의 열린 화면 수량 갱신 문제는 2단계의 전경·수동 갱신으로 수정했습니다. 2단계에서 길찾기 상태 캐시를 최신 응답으로 교체하고, 오래되거나 오차가 큰 위치를 걸러내며, 경로 대여소 수량을 별도 갱신하도록 수정했습니다. iOS 지도 경계는 화면 내부 좌표로 계산하고, 추천 경로에는 서버의 기본 후보 비교를 유지했습니다. 3단계 검토 후에는 위치의 유효 시간이 끝나도 이미 선택한 좌표와 표시 중인 경로를 유지하도록 수정했습니다.

### Android 에뮬레이터 개발 빌드 검증

Windows에서 Android Studio 없이 공식 Android 명령줄 SDK를 프로젝트의 무시 경로 `.local/android-sdk`에 설치했습니다. LDPlayer 14.0.36.0의 Android 14에서 ARM64 실행 호환성과 개발 APK 설치를 확인했습니다. Windows가 기본 ADB 포트 `5555`를 예약한 환경이라 이 인스턴스의 ADB 연결에 루프백 `127.0.0.1:15555`를 사용했습니다.

Windows의 드라이브 별칭과 실제 경로가 섞인 첫 Gradle 빌드는 AsyncStorage 코드 생성에서 중단됐습니다. 짧은 실제 경로에 소스를 준비하고 `npm ci`와 Android 프로젝트 생성을 다시 실행한 뒤, 생성된 `android/`에서 아래 명령이 통과했습니다. 이 개발 APK는 디버그 인증서로 서명됐으며 Metro에 연결해 실행합니다.

```powershell
.\gradlew.bat :app:assembleDebug -PreactNativeArchitectures=arm64-v8a --no-daemon
```

웹 개발 서버와 LDPlayer 앱은 모두 호스트 `127.0.0.1:8080`의 같은 백엔드에 연결했습니다. 웹은 Vite 프록시를 사용하고, 앱은 `adb reverse tcp:8080 tcp:8080`과 `EXPO_PUBLIC_API_BASE_URL=http://127.0.0.1:8080`을 사용합니다. Metro는 별도 포트 `18088`을 사용했습니다.

실시간 대여소 마커와 카카오 지도 타일, 축소 시 묶음 표시와 선택 후 확대, 마커에서 상세 열기, 장소·대여소 검색과 지도에서 출발지 선택, 실제 추천 경로 표시, 앱 종료 후 즐겨찾기 복원을 에뮬레이터에서 확인했습니다. 웹과 앱의 주변 대여소·상세·길찾기·즐겨찾기 화면을 논리 너비 360에서 비교했습니다. 앱의 도보 경로선은 네이티브 지도에서 실선으로 렌더링됩니다. Android 실기기 GPS와 iOS 기기 동작은 미검증입니다.

LDPlayer에서 실제 공용 API에 연결해 대여소 번호 `2231`(`ST-761`)을 검색했습니다. 대여소 상세의 시간대 비교에서 `15:10`을 선택하자 `15:00`의 실제 관측값 `41대`가 표에 표시됐습니다.

화면 변경 후 모바일 타입 검사와 테스트 11개, 웹 테스트 82개와 운영 빌드, Android·iOS Hermes 번들 생성이 통과했습니다.

## 내부 배포와 스토어 준비

표시 이름은 **따릉라이더**, Android 패키지명과 iOS 번들 ID는 **`com.ddareungi.rider`**입니다. 앱 버전은 `0.1.0`, Android `versionCode`와 iOS `buildNumber`는 각각 `1`입니다. `eas.json`은 로컬 버전 설정을 사용합니다. 다음 제출 전 실제 스토어에 기록된 버전·빌드 번호를 확인하고 필요한 값을 올립니다. [Expo 빌드 설정](https://docs.expo.dev/eas/json/) · [Expo 버전 관리](https://docs.expo.dev/build-reference/app-versions/)

| EAS 프로필 | 환경 | 빌드 용도 |
| --- | --- | --- |
| `development` | `development` | Metro와 연결하는 개발 클라이언트 |
| `preview` | `preview` | 내부 테스트용 Android APK와 등록된 iOS 기기에 설치할 IPA |
| `production` | `production` | 스토어 제출용 Android AAB와 iOS IPA |

`preview`와 `production`에서는 `EXPO_PUBLIC_API_BASE_URL`이 경로·쿼리·사용자 인증이 없는 HTTPS 원본 주소이고 `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY`가 있어야 합니다. **승인된 환경별 API 주소와 네이티브 키**를 해당 EAS 환경에 설정합니다. 로컬 설정 검사에서 빈 주소, HTTP, API 경로, 쿼리, URL 인증 정보, 잘못된 포트, 키 누락 거부를 확인했습니다. 정상 형식의 가상 값으로 Android·iOS 식별자, 빌드 번호와 iOS ATS·권한 설정을 확인했습니다. [EAS 환경변수](https://docs.expo.dev/eas/environment-variables/) · [Expo 내부 배포](https://docs.expo.dev/build/internal-distribution/)

### 승인 후 실행 절차

1. 조직의 Expo 프로젝트를 연결하고 `preview` 환경 값을 설정합니다. Android는 내부 빌드 서명 인증서의 카카오 키 해시를 등록합니다. iOS는 내부 테스트 기기의 UDID를 Apple 계정에 등록해 빌드의 프로비저닝 프로파일에 포함합니다. 두 플랫폼의 앱 식별자가 카카오 Developers의 등록 정보와 일치하는지 확인합니다. [Expo 첫 빌드 설정](https://docs.expo.dev/build/setup/) · [Expo 내부 배포](https://docs.expo.dev/build/internal-distribution/)
2. 클라우드 빌드가 승인되면 `Ddareungi-rider/mobile/`에서 `npx eas-cli build --platform all --profile preview`를 실행합니다. Android ARM 기기에는 APK를 설치하고, 등록한 iOS 기기에서는 내부 배포 링크로 IPA를 설치합니다. 설치한 빌드를 Metro 없이 열어 위 실기기 시나리오를 확인합니다. [Expo 내부 배포](https://docs.expo.dev/build/internal-distribution/) · [iOS 개발자 모드](https://docs.expo.dev/guides/ios-developer-mode/)
3. 내부 검증을 마친 뒤 운영 환경 값과 서명을 준비합니다. 스토어용 빌드가 승인되면 같은 디렉터리에서 `npx eas-cli build --platform all --profile production`을 실행합니다. 완성된 AAB·IPA, 스토어 개인정보 신고와 공개 처리방침을 검토한 뒤 제출 승인 범위에서 업로드합니다. [Expo 스토어 제출](https://docs.expo.dev/deploy/submit-to-app-stores)

| 단계 | 상태 |
| --- | --- |
| 로컬 빌드 설정 | 프로필, 빌드 번호, 기존 로고 기반 아이콘, 사용 중 위치 권한 문구, Android 백업 비활성화, 앱의 **개인정보** 안내 준비. 가상 릴리스 설정으로 Android 프로젝트를 생성해 외부 저장소 읽기·쓰기와 화면 오버레이 권한 제거 확인 |
| 서명과 설치 산출물 | 대기. 조직의 Expo·Apple Developer·Google Play 계정 권한, 스토어 식별자 예약 여부, Android 서명과 카카오 키 해시, iOS 서명·프로비저닝과 기기 등록 확인 필요 |
| 내부 설치·검증 | 대기. 승인된 HTTPS 환경으로 내부 빌드를 만들고 각 실기기에서 위 표의 시나리오를 재확인 |
| 스토어 제출·공개 | 대기. 운영 주체·문의처·공개 개인정보 처리방침 URL, App Store 개인정보 신고와 Google Play 데이터 보안 양식, 빌드에 포함된 SDK의 개인정보 매니페스트와 요구 사유 API 사용 확인 필요 |

홈의 **개인정보** 안내에서 서버와 카카오로 전송되는 위치·검색 데이터와 기기에 저장되는 즐겨찾기 ID·최근 검색어·사용 시각을 확인할 수 있습니다. 공개 처리방침과 스토어 신고에는 운영 환경에서 실제로 처리하는 데이터, 보유 기간, 제3자 SDK 및 사용자 삭제 절차를 확인해 반영합니다. 클라우드 빌드, 내부 배포, 제출은 승인된 계정과 범위에서 진행합니다. [Apple App Store 개인정보 세부 정보](https://developer.apple.com/app-store/app-privacy-details/) · [Apple 개인정보 매니페스트](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files) · [Google Play 데이터 보안](https://support.google.com/googleplay/android-developer/answer/10787469)
