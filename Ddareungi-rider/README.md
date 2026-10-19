# 따릉라이더 · ddareungrider

PC와 모바일 브라우저에서 주변 따릉이 대여소를 조회하는 웹 서비스입니다.

- `web/`: React + TypeScript + Vite 웹 클라이언트
- `backend/`: Java 21 / Spring Boot REST API
- `mobile/`: 이전 Expo PoC 보관본. 현재 실행 대상이 아닙니다.

로컬 설치, `.env`, 샘플/실제 데이터 실행 및 중지는 [최상위 README](../README.md)를 참고하세요.
네이버 지도에서 카카오맵으로 전환한 이유, 인증을 먼저 확인한 과정, 코드 변경과 검증 결과는 [카카오 전환 기록](../README.md#네이버-지도에서-카카오맵으로-전환한-이유와-과정-2026-10-10)에 정리했습니다.
프로젝트 최상위에서 실행합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Mode Sample
```

접속: http://localhost:5173. 개발 서버는 `/api`를 백엔드로 프록시합니다.

`local` 프로필은 외부 DB 없이 강남역 가상 데이터 3건을 제공합니다.
`infra` 프로필은 수집기가 생성한 MySQL 대여소와 Redis 최신 수량을 결합합니다. 수량 미수집·만료·Redis 장애 시 `availableBikeCount`와 `updatedAt`은 null입니다.
카카오 지도 SDK, 장소·주소 검색, 대여소 검색과 즐겨찾기, 서비스 내부의 도보→따릉이→도보 길찾기가 구현되어 있습니다. 같은 카카오 Developers 앱의 JavaScript 키와 REST API 키를 프로젝트 최상위 `.env`에 설정합니다. AI 예측 영역은 유지하며 학습 모델은 아직 연결되지 않았습니다. [길찾기 실행 안내](../docs/routing.md)를 참고하세요.

백엔드 테스트: `gradlew.bat :backend:test`.
웹 검증: `cd web` 후 `npm run build`.
