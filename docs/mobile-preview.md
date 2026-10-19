# 모바일 HTTPS 테스트

`npm --prefix Ddareungi-rider/web run build` 후 `node scripts/serve-mobile-preview.mjs`를 실행하면 127.0.0.1:5174에서 빌드 결과와 `/api/v1/` 백엔드 프록시를 제공합니다. 백엔드 8080 포트가 먼저 실행되어야 합니다. 화면 변경 후 다시 빌드하면 다음 요청에 반영됩니다.

Cloudflare cloudflared를 설치한 환경에서는 `cloudflared tunnel --url http://127.0.0.1:5174 --no-autoupdate`로 임시 HTTPS 주소를 만들 수 있습니다. 이 명령은 화면과 API를 외부에 공개하므로 공개할 데이터와 접속 범위를 확인한 후 실행합니다. 터널을 종료하면 접속도 종료됩니다. 주소는 재실행 시 달라질 수 있습니다.

카카오디벨로퍼스의 해당 앱 JavaScript SDK 도메인에 발급된 HTTPS 주소를 등록해야 지도가 표시됩니다. 휴대폰 브라우저에서 위치 권한을 허용합니다. localhost는 HTTP 위치 기능 예외지만 PC 내부 IP는 예외가 아닙니다.

이 서버는 테스트 전용이며 소스·환경 파일과 actuator 경로를 제공하지 않습니다. 인증 시스템이나 운영 배포를 대체하지 않습니다. `.local`의 도구 실행 파일, 로그, PID, 터널 주소는 Git에 포함하지 않습니다.
