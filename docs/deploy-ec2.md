# EC2 배포(CD) — Phase 3 O7

작성: 2026-11-17, 기준 커밋 `c768038`. 갱신: 2026-11-17 독립 리뷰 M2·M3·L5·L7·L8 반영(전용 SSM 문서, 점검·강제 재시작, 스모크 강화, 신호 롤백). 근거: [Phase 3 명세](phase3-2026-11-17.md) 1장 기본값 14~20·24, 5장 O7.
표기: **[사실]** 이 저장소 코드·하네스 실행으로 확인 / **[추정]** 설계 판단·일반 지식 / **[미검증]** 실제 환경 실행 확인 필요.

> **실제 AWS·GitHub Actions에서 한 번도 실행하지 않았다.** 워크플로·스크립트·정책은 문법 검사와 가짜 명령 하네스로만 확인했다. 첫 배포는 아래 체크리스트를 따라 사용자가 직접 확인해야 한다.

## 1. 구성 요약

```
push main ─▶ CI(ci.yml) 성공 ─▶ Deploy(deploy.yml, environment: production 승인)
   OIDC로 역할 assume ─▶ ECR push(태그=커밋 SHA, immutable) ─▶ digest 조회
   ─▶ SSM Run Command(전용 문서 ddareungi-deploy, 파라미터 패턴 검증)
   ─▶ EC2: /opt/ddareungi/bin/deploy.sh(sha256 = 커밋 버전일 때만 실행) <backend@digest> [<web@digest>]
수동 실행(workflow_dispatch action): deploy(롤백) | force-restart | maintenance-on | maintenance-off

EC2(단일 호스트)
  nginx :80/:443 ──/api/──▶ upstream ddareungi_backend ─▶ 127.0.0.1:18081(blue) | 127.0.0.1:18082(green)
        └─ /, /assets ─▶ /srv/ddareungi/web/current → releases/<digest>
  nginx 127.0.0.1:18080 : 배포 스모크 전용(같은 locations)
  docker network "ddareungi": backend-blue/green(:8080 API, :8090 actuator 미공개), prometheus, grafana
```

| 파일 | 역할 |
|---|---|
| `.github/workflows/deploy.yml` | `workflow_run`(CI success·push·main) 빌드+배포, `workflow_dispatch(action, image_tag)` 롤백·강제 재시작·점검 켜기/끄기(빌드 없음) |
| `deploy/ec2/deploy.sh` | blue/green 전환, `--force-restart`, `maintenance on\|off`. 실패·INT/TERM 시 이전 상태로 되돌리고 exit ≠ 0 |
| `deploy/aws/ssm-document-deploy.json` | 전용 SSM Command 문서 템플릿: 호스트의 `deploy.sh`만 실행, 파라미터 정규식·허용값 검증, 스크립트 sha256 확인 |
| `deploy/ec2/nginx/{ddareungi,locations,upstream-backend}.conf` | 사이트, 공용 location, 초기 upstream |
| `deploy/ec2/backend.env.example`, `deploy.env.example` | 비밀 아닌 백엔드 설정, 호스트 배포 설정 |
| `deploy/ec2/bootstrap.md` | EC2 1회 준비 |
| `deploy/ec2/test/` | 가짜 docker·nginx·curl·aws 하네스 |
| `deploy/web/Dockerfile` | 웹 정적 번들 운반용 `scratch` 이미지(`/site`) |
| `deploy/aws/*.json` | OIDC 신뢰 정책, GitHub 역할 최소 권한(전용 문서만 SendCommand), EC2 instance 정책, ECR lifecycle(20개), SSM 문서 |
| `deploy/observability/` | prometheus+grafana compose(127.0.0.1 바인딩), 대시보드 초안 |

## 2. 결정과 근거

| 항목 | 내용 | 구분 |
|---|---|---|
| 접근 | SSM Run Command만 사용, 인바운드 22·장기 키 없음 | 기본값 15 |
| 웹 | 같은 EC2 nginx 정적 서빙(같은 origin, CORS 비움). 번들은 ECR의 `scratch` 이미지로 전달해 digest 고정·lifecycle을 백엔드와 공유 | 기본값 17, 전달 방식은 [추정] 설계 |
| 이미지 | 태그=40자 커밋 SHA, IMMUTABLE, 배포는 digest, `latest` 미사용, `provenance/sbom: false`로 태그당 단일 manifest | 기본값 19 |
| 비밀 | SSM Parameter Store SecureString → 0600 임시 env 파일 → `docker run --env-file` 후 삭제 | 기본값 20 |
| 프록시 IP | nginx가 `X-Forwarded-For`를 `$remote_addr`로 **덮어씀**, `Forwarded` 제거. 앱은 `SERVER_FORWARD_HEADERS_STRATEGY=native`(env, yml 미수정). `deploy.sh`가 `docker run -e`로 강제 | 기본값 24 |
| 롤백 | `workflow_dispatch(image_tag=이전 SHA)` → 같은 blue/green 경로. 백엔드는 DB 마이그레이션이 없어 이미지 롤백이 안전 [사실: Flyway는 수집기에만] | 명세 5장 |
| 스모크 | ① 새 색 포트 직접(`127.0.0.1:1808x`), ② 전환 후 nginx 경유(127.0.0.1:18080) 두 번. 각각 `GET /api/client-config?platform=web` 200 + `"maintenance"`가 env 값과 일치, 점검이 아니면 `GET /api/v1/routes/status` 200 + `"configured":true`. routes/status는 Rate Limit `stations` 그룹(분당 300), client-config는 Rate Limit 밖 [사실: `RateLimitGroup`, `ClientConfigController`] | 리뷰 M2·L5 |
| 스크립트 전달 | **변경(리뷰 L8)**: 이전에는 `AWS-RunShellScript`에 base64 스크립트를 실어 보냈다(= GitHub 역할이 인스턴스에서 임의 root 명령 가능). 이제 전용 문서 `ddareungi-deploy`가 호스트에 설치된 `/opt/ddareungi/bin/deploy.sh`만 실행하고, 워크플로가 보낸 커밋 버전 sha256과 다르면 exit 3으로 거부한다. 대가: `deploy.sh`가 바뀐 커밋은 배포 전에 관리자가 호스트 스크립트를 갱신해야 한다(8장) | [추정] 설계 |
| 점검 모드 | env 기반(결정 21). `deploy.sh maintenance on\|off`가 `backend.env`의 `CLIENT_WEB_MAINTENANCE`를 바꾸고 활성 이미지를 반대 색으로 재생성(blue/green·롤백 재사용). 실패 시 env 파일도 복원 | 리뷰 M3 |

## 3. deploy.sh 동작 [사실: 코드·하네스]

사용법: `deploy.sh [--force-restart] <backend@digest> [<web@digest>]`, `deploy.sh maintenance on|off`.

1. 인자 검증: `<registry>/<repo>@sha256:<64hex>`만 허용(태그 거부, exit 2). `maintenance`는 `on|off`만. `flock`으로 동시 실행 거부.
2. 활성 색 = upstream 파일이 가리키는 포트의 색, 단 그 컨테이너가 실행 중일 때만. 없으면 첫 배포로 보고 blue.
3. 활성 이미지가 요청 digest와 같고 웹도 같으면 아무것도 하지 않음(멱등). `--force-restart`·`maintenance`는 같은 digest라도 반대 색으로 재생성한다(실행 중 이미지라 pull 생략).
4. ECR 로그인(인스턴스 역할) → pull(digest) → 웹 번들 추출(`docker create`+`docker cp /site`, `releases/<digest 앞 16자>`).
5. `maintenance`: `backend.env`를 `STATE_DIR/backend.env.prev`로 백업 후 `CLIENT_WEB_MAINTENANCE=true|false`를 원자 교체(소유자·권한 유지, 한 줄만 유지).
6. SSM 비밀: `out=$(aws ssm get-parameters-by-path ...)`로 먼저 받아 **조회 실패면 즉시 중단**(이전에는 `done < <(...)`라 AccessDenied를 무시하고 비밀 없이 기동했다, 리뷰 M2). 경로가 설정됐는데 0개여도 중단. 값은 메모리에만 두고(롤백 로그 마스킹용) 0600 임시 env 파일로 넘긴 뒤 삭제. SSM에 `CLIENT_WEB_MAINTENANCE`가 있으면 그것이 이기므로 `maintenance` 요청과 어긋나면 중단.
7. 반대 색 컨테이너를 `127.0.0.1:<18081|18082>:8080`만 publish해 시작. `--network ddareungi`, `--restart unless-stopped`, `--env-file backend.env` + SSM 비밀, `MANAGEMENT_SERVER_PORT=8090`·`SERVER_FORWARD_HEADERS_STRATEGY=native` 강제.
8. `docker exec <새 컨테이너> curl -fsS http://localhost:8090/actuator/health/readiness` 반복(기본 240초). 컨테이너가 죽으면 즉시 실패.
9. **새 색 직접 스모크**(nginx 변경 전): client-config `maintenance` 일치 + (점검 아니면) routes/status `"configured":true`. 실패 → 새 컨테이너 제거, nginx는 손대지 않음.
10. upstream 파일 백업 → 같은 디렉터리 임시 파일 작성 → `mv` 원자 교체 → `nginx -t && nginx -s reload`.
11. nginx 경유 스모크(같은 판정, 기본 5회·2초 간격). 실패 → upstream 복원·reload·새 컨테이너 제거.
12. 웹: `current` 심링크를 `ln -sfn`+`mv -T`로 원자 교체 → `GET /` 200. 실패 → 웹·백엔드 모두 되돌림.
13. 상태 기록을 `*.current.new`로 **먼저 작성**(실패 → 롤백). 그 뒤 커밋: `.new`를 `current`로 이동, 이전 값은 `previous`(같은 digest 재시작이면 회전 안 함), `history.log`에 `deploy|restart|maintenance-on|off` 기록. 커밋 후 이동·기록 실패는 WARN만(새 버전이 이미 서비스 중).
14. drain(기본 30초) 후 이전 색 `docker stop -t 30`. 오래된 웹 릴리스(5개 초과)와 dangling 이미지 정리.

롤백 시 새 컨테이너 로그는 `ROLLBACK_LOG_LINES`(기본 30, 0이면 출력 안 함)줄만, SSM에서 읽은 값과 `password=`/`token:`/`Authorization:` 형태 값을 `***`로 가려 출력한다(리뷰 L7). **이 출력은 SSM을 거쳐 GitHub Actions 로그에 남는다. 공개 저장소면 Actions 로그도 공개이므로 `ROLLBACK_LOG_LINES=0`을 권장**하고, 실패한 컨테이너는 제거되며 그 로그도 함께 사라지므로, 0으로 두면 원인은 SSM 세션에서 같은 digest를 수동으로 띄워 재현해야 한다(대안: 작은 값, 예 10).

신호: INT/TERM/HUP은 EXIT 트랩으로 넘어가 커밋 전이면 위와 같은 롤백을 수행한다(롤백 중 두 번째 신호는 무시). **SIGKILL은 잡을 수 없다**: SSM 실행 시간 초과·`cancel-command`가 어떤 신호를 보내는지는 [미검증]이며, 중간 상태가 남으면 8장의 수동 복구를 따른다.

종료 코드: 0 성공·변경 없음, 1 배포 실패(이전 버전 유지), 2 인자 오류, 129/130/143 신호로 중단(롤백 수행), SSM 문서가 3(호스트 스크립트 불일치·없음).

스모크가 응답한 색 [한계, 리뷰 L5]: 직접 스모크는 새 색 포트로 보내므로 응답 주체가 확정된다. nginx 경유 스모크는 응답 색을 식별하는 헤더가 없어 "라우팅이 동작한다"만 확인한다. reload 후 새 연결은 새 워커(새 upstream만 앎)가 받으므로 이전 색이 응답할 가능성은 낮다고 본다 [추정].

## 4. 하네스 결과 [사실: 2026-11-17 실행]

`deploy/ec2/test/run-harness.sh` — 실제 `deploy.sh`를 가짜 `docker`/`nginx`/`curl`/`aws`(PATH 앞)로 실행. root·Docker 데몬·AWS 불필요.

| # | 시나리오 | 결과 |
|---|---|---|
| 1 | 태그 참조(`:latest`) 거부 | exit 2, 컨테이너 없음 |
| 2 | 첫 배포(백엔드+웹) | blue 기동, upstream 18081, 관리 포트 미공개, forwarded·관리 포트 강제, 직접·nginx 스모크, SSM 비밀 env 파일 전달 후 삭제·로그 미노출, 웹 current 전환 |
| 3 | 두 번째 배포 | green 전환, blue 정지(보존), current/previous 기록 |
| 4 | 같은 digest 재실행 | "nothing to do", reload 0회 |
| 5 | readiness 실패 | exit 1, 새 컨테이너 제거, nginx 미변경, green 유지 |
| 6 | 기동 직후 컨테이너 종료 | 타임아웃 전 즉시 실패, green 유지 |
| 7 | 전환 후 nginx 경유 스모크 실패(503) | exit 1, upstream 18082 복원, reload 2회(전환+복원), 새 컨테이너 제거 |
| 8 | 전환 후 `nginx -t` 실패 | exit 1, upstream 파일 복원, 새 컨테이너 제거 |
| 9 | 웹 스모크 실패 | exit 1, 웹 current와 백엔드 upstream 모두 복원 |
| 10 | 이전 digest로 롤백 배포 | blue에 이전 이미지, green 정지, current/previous 갱신 |
| 11 | 동시 실행 | 잠금으로 exit 1, 기존 상태 유지 |
| 12 | SSM 조회 실패(AccessDenied 흉내, exit 254) | exit 1, 컨테이너·nginx 변경 없음, 비밀 파일 없음 |
| 13 | SSM 경로 0개 | exit 1 "returned no parameters" |
| 14 | 새 색이 `"configured":false` | 직접 스모크에서 exit 1, reload 0회, 새 컨테이너 제거, 롤백 로그 마스킹(SSM 값·`password=`·`Authorization:`) |
| 15 | nginx 경유에서만 `configured:false` | 직접 스모크 통과 → 경유 스모크 실패 → upstream 복원(reload 2회) |
| 16 | `ROLLBACK_LOG_LINES=0` | 컨테이너 로그 미출력 |
| 17 | nginx 전환 직후 SIGTERM | exit 143, upstream·live 복원, 새 컨테이너 제거, 상태 불변 |
| 18 | 커밋 전 상태 파일 쓰기 실패 | exit 1, 롤백 |
| 19 | `--force-restart` 같은 digest | 반대 색으로 재생성, pull 없음, previous 회전 안 함, history `restart` |
| 20 | `maintenance on` | env 파일 true(권한 0600 유지), 새 색 env true, client-config `maintenance=true` 스모크(routes/status 생략) |
| 21 | `maintenance off` 중 readiness 실패 | env 파일 true로 복원, 기존 색 유지 |
| 22 | SSM에 같은 이름 파라미터가 있어 `off`가 무효 | exit 1, env 복원, 컨테이너·nginx 변경 없음 |
| 23 | `maintenance off` | env false(한 줄만), routes/status `configured` 재검사 |
| 24 | `maintenance maybe` | exit 2 |
| 25 | 점검 후 일반 배포 | 정상 전환, previous 갱신 |
| 26 | SSM 문서(python3로 `{{ }}` 치환·`allowedPattern`/`allowedValues` 적용 후 `sh` 실행, 스크립트 경로만 스텁으로 교체) | 4개 action 인자 매핑, backendRef 없음 exit 2, sha256 불일치 exit 3(스크립트 미실행), 따옴표 주입·태그·임의 action 거부 |

결과: **86 passed, 0 failed**(이전 42). 변형 검사: 복사본에서 ① SSM 실패를 무시하게 바꾸면 1개, ② 0개 검사 제거 시 21개, ③ `configured` 검사 끔 19개, ④ TERM 트랩 제거 12개, ⑤ env 복원 제거 2개, ⑥ 마스킹 제거 1개, ⑦ 직접 스모크 제거 4개 검사가 FAIL로 바뀌었다(①은 0개 검사가 이중 방어로 잡음).
추가: `bash -n`, PyYAML로 `deploy.yml` 파싱, JSON 파싱(`deploy/aws/*.json`), 액션 40자 SHA 고정, `run:` 안 `${{ }}` 직접 삽입 없음, `Resolve target` 스크립트를 입력 조합 8개(정상·빈 태그·점검+태그·`x;id`·`$(id)`)로 실행, SSM 단계의 파라미터 생성부를 5개 조합(태그 ref·`AWS-RunShellScript`·`a';id` 문서명 거부)으로 실행.

하네스가 증명하지 않는 것: 실제 Docker·nginx 문법·reload 동작, ECR 인증, SSM 문서를 실제 SSM Agent가 해석·치환하는 방식, 신호 전달 방식, 타이밍, 디스크·권한.

## 5. 액션 고정 [사실: 2026-11-17 `git ls-remote --tags`로 조회, 주석 태그는 `^{}` 커밋 사용]

| 액션 | 태그 | 커밋 |
|---|---|---|
| actions/checkout | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| actions/setup-node | v7.1.0 | `949feb2413d6458794dcd2491c4babbbce0c15c1` |
| aws-actions/configure-aws-credentials | v6.3.0 | `e1253824e5c10ff9df46874f81ed3ec929e19cfd` |
| aws-actions/amazon-ecr-login | v2.1.7 | `03f1aad4c6c7ffd436567f42f9384779290529bd` |
| docker/setup-buildx-action | v4.4.1 | `f87e5991a6d7451dcb8d9637bfbc97413f497069` |
| docker/build-push-action | v7.4.0 | `c3c9e263c25d99ce0380d002d59b67737d91b0dc` |

각 SHA의 `action.yml`을 내려받아 사용한 입력·출력 이름(`role-to-assume`, `mask-aws-account-id`, `registry`, `provenance`, `sbom`, `cache-from/to`, `cache-dependency-path` 등)이 존재함을 확인했다. 실제 실행 동작은 [미검증].
참고(보고만, 수정 안 함): 기존 `ci.yml`은 `actions/checkout@v7` 등 **태그**로 참조한다. 같은 방식으로 SHA 고정을 권장한다. `ci.yml`의 docker job은 빌드만 하고 버리므로 배포 시 이미지를 한 번 더 빌드한다(gha 캐시로 완화, 이후 CI에서 push로 통합 검토).

## 6. 사용자 제공 값

| 값 | 위치 | 비고 |
|---|---|---|
| AWS 계정 ID | `deploy/aws/*.json`의 `<ACCOUNT_ID>`(로컬 사본) | 커밋하지 않음 |
| 리전 | GitHub `vars.AWS_REGION`, `/etc/ddareungi/deploy.env` | ap-northeast-2 가정 [추정] |
| ECR 저장소명(백엔드·웹) | `vars.ECR_BACKEND_REPOSITORY`, `vars.ECR_WEB_REPOSITORY`, 정책 `<ECR_*>` | 웹을 비우면 백엔드만 배포 |
| GitHub OIDC provider, 배포 역할 ARN | IAM, `vars.AWS_DEPLOY_ROLE_ARN` | 신뢰 정책 sub는 `repo:mnjihwan/Ddareungi-rider:environment:production` |
| EC2 인스턴스 ID | `vars.EC2_INSTANCE_ID`, 정책 `<EC2_INSTANCE_ID>` | 태그 대상 지정은 미구현(단일 인스턴스) |
| SSM 배포 문서 이름 | `vars.SSM_DEPLOY_DOCUMENT`(비우면 `ddareungi-deploy`), 정책 `<SSM_DEPLOY_DOCUMENT>` | `AWS-*` 문서는 워크플로가 거부 |
| Environment 승인자 | GitHub Environment `production` | main 브랜치만 |
| 도메인·TLS 방식 | `ddareungi.conf`의 `<DOMAIN>`, TLS 블록 | certbot/ALB 등 미정. ALB를 앞에 두면 realip 설정 필수 |
| MySQL·Redis·수집기 위치 | `backend.env`의 `MYSQL_URL`, `REDIS_HOST` | 같은 호스트면 `ddareungi` 네트워크 연결 |
| 런타임 비밀 | SSM `/ddareungi/production/backend/*` | MYSQL_PASSWORD, KAKAO_MAPS_REST_API_KEY, SEOUL_API_KEY(+ORS, Redis AUTH) |
| Kakao JS 키 | `vars.KAKAO_MAPS_JAVASCRIPT_KEY` | 번들에 포함되는 공개 키, 카카오 콘솔에 운영 도메인 등록 필요 |
| 이미지 플랫폼 | `vars.IMAGE_PLATFORM`(기본 linux/amd64) | Graviton이면 변경 |
| Grafana 관리자 암호 | `/etc/ddareungi/observability.env` | |

## 7. 첫 배포 체크리스트

1. [ ] `deploy/ec2/bootstrap.md` 1~8 완료(SSM 문서 생성·`deploy.sh` 설치 포함), `sudo nginx -t` 통과.
2. [ ] **프록시 forwarded 설정 확인(필수)**: `backend.env`에 `SERVER_FORWARD_HEADERS_STRATEGY=native`, nginx `locations.conf`의 `proxy_set_header X-Forwarded-For $remote_addr`. 앞단에 ALB/CloudFront가 있으면 nginx `set_real_ip_from`/`real_ip_header`를 먼저 설정. **틀리면 모든 사용자가 한 Rate Limit 키를 공유해 대량 429가 난다**(Rate Limit 키는 `getRemoteAddr()` [사실: `RateLimitInterceptor`]).
3. [ ] **MySQL에 대여소 데이터가 있음**(수집기 1회 이상 실행): 없으면 readiness `stationCatalog`가 DOWN이라 배포가 실패하고 기존 버전이 유지된다.
4. [ ] SSM 파라미터 등록(`aws ssm get-parameters-by-path --path /ddareungi/production/backend/ --query 'Parameters[].Name'`로 이름만 확인).
5. [ ] GitHub `production` 환경 변수·승인자 설정, 장기 키 없음.
6. [ ] 첫 배포는 `workflow_dispatch`로 할 수 없다(ECR에 이미지가 없음). main push → CI 성공 → Deploy 승인 순서로 진행.
7. [ ] 배포 로그(Actions의 "deploy.sh stdout/stderr" 그룹)에서 `ready`, `smoke ok (direct ...)`, `smoke ok (via nginx)`, `committed` 확인. exit 3이면 호스트 `deploy.sh`가 커밋 버전과 다르다(8장).
8. [ ] 외부에서 확인: `curl -i https://<DOMAIN>/api/v1/routes/status`(200), 브라우저에서 지도 표시, 같은 IP에서 연속 호출 시 429가 **그 IP에만** 걸리는지(가능하면 두 네트워크에서) 확인.
9. [ ] 의도적 실패 1회(예: 잘못된 `MYSQL_URL`로 readiness 실패) → 기존 버전 유지 확인 후 원복.
10. [ ] 롤백 리허설: `workflow_dispatch(action=deploy, image_tag=<직전 SHA>)`.
11. [ ] 점검 리허설: `action=maintenance-on` → 외부에서 `/api/v1/routes/status` 503 `MAINTENANCE_MODE`, `/api/client-config?platform=web`의 `maintenance:true`, 웹 점검 화면 → `action=maintenance-off`로 복귀.
12. [ ] 공개 저장소면 `deploy.env`에 `ROLLBACK_LOG_LINES=0` 여부 결정.

## 8. 운영

- 롤백: Actions → Deploy → Run workflow(main) → `action=deploy`, `image_tag`에 이전 성공 커밋 40자 SHA. ECR에 남은 최근 20개만 가능.
- **점검 켜기/끄기**(결정 21, 리뷰 M3): Actions → Deploy → Run workflow(main) → `action=maintenance-on`(또는 `maintenance-off`), `image_tag`는 비움. 환경 승인 후 SSM 문서가 `deploy.sh maintenance on|off`를 실행해 `/etc/ddareungi/backend.env`의 `CLIENT_WEB_MAINTENANCE`를 바꾸고 **활성 이미지를 반대 색으로 재생성**한다(readiness·직접/경유 스모크·롤백 동일, 두 색이 잠시 함께 떠 메모리 2배). 안내 문구는 `CLIENT_WEB_MAINTENANCE_MESSAGE`(backend.env, 한 줄)를 SSM 세션에서 바꾼 뒤 `force-restart`로 반영. 점검 중에는 `/api/v1/**` 503이므로 스모크는 client-config만 본다. 같은 이름의 SSM 파라미터가 있으면 그것이 이기므로 두지 않는다(있으면 deploy.sh가 거부).
- **설정만 바꾼 재기동**: `backend.env`나 SSM 비밀을 바꾼 뒤 `action=force-restart`, `image_tag=<현재 SHA>`. 같은 digest여도 반대 색으로 재생성한다. 호스트에서 직접: `sudo /opt/ddareungi/bin/deploy.sh --force-restart "$(cat /var/lib/ddareungi/deploy/backend.current)"`.
- **deploy.sh 갱신**(리뷰 L8의 대가): `deploy/ec2/deploy.sh`가 바뀐 커밋을 배포하면 SSM 문서가 exit 3으로 거부한다. 관리자가 **GitHub 역할이 아닌 자신의 자격 증명**으로 호스트 스크립트를 바꾼 뒤 워크플로를 다시 실행한다. 예(로컬 저장소 루트, 관리자 권한):
  ```bash
  sum=$(sha256sum deploy/ec2/deploy.sh | cut -d' ' -f1); b64=$(base64 -w0 deploy/ec2/deploy.sh)
  jq -n --arg b64 "$b64" --arg sum "$sum" '{commands: [
    "set -eu", "install -d -m 0755 /opt/ddareungi/bin",
    ("printf %s " + $b64 + " | base64 -d > /opt/ddareungi/bin/deploy.sh.new"),
    ("echo \"" + $sum + "  /opt/ddareungi/bin/deploy.sh.new\" | sha256sum -c --quiet -"),
    "chmod 0755 /opt/ddareungi/bin/deploy.sh.new", "mv -f /opt/ddareungi/bin/deploy.sh.new /opt/ddareungi/bin/deploy.sh"]}' > install.json
  aws ssm send-command --document-name AWS-RunShellScript --instance-ids <EC2_INSTANCE_ID> --parameters file://install.json
  ```
  [미검증] 명령 길이(스크립트 base64 약 33 KB)가 파라미터 한도를 넘으면 Session Manager로 붙여넣거나 S3로 전달한다.
- **SSM 문서 갱신**: `aws ssm update-document --name ddareungi-deploy --content file://deploy/aws/ssm-document-deploy.json --document-format JSON --document-version '$LATEST'` 후 `aws ssm update-document-default-version --name ddareungi-deploy --document-version <새 버전>`. GitHub 역할에는 문서 수정 권한이 없다.
- 수동 긴급 복구(SSM 세션, SIGKILL 등으로 중간 상태가 남은 경우 포함): `history.log`·`backend.current`와 upstream 파일·`docker ps -a`를 대조한다. 정지된 이전 색 컨테이너가 남아 있으면 `docker start ddareungi-backend-<색>` 후 readiness 확인, `/etc/nginx/ddareungi/upstream-backend.conf`의 포트를 바꾸고 `nginx -t && nginx -s reload`. 상태 기록은 `/var/lib/ddareungi/deploy/history.log`.
- 관측 열람(인바운드 없이):
  ```bash
  aws ssm start-session --target <EC2_INSTANCE_ID> --document-name AWS-StartPortForwardingSession \
    --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'   # Grafana → http://localhost:3000
  ```
  Prometheus는 `portNumber` 9090. 로컬에 Session Manager plugin 필요.
- Prometheus는 `ddareungi-backend-blue:8090`, `ddareungi-backend-green:8090`을 모두 scrape하며 비활성 색의 `up=0`은 정상이다. 대시보드(`grafana/dashboards/ddareungi-backend.json`)는 O8 명세의 meter 이름(`ddareungi.provider.calls` → `ddareungi_provider_calls_seconds_*`, `ddareungi.realtime.*` 등) 기준 **초안**이며 O8 병합 후 `/actuator/prometheus` 출력과 대조해 고쳐야 한다. Provider p95 패널은 해당 Timer에 히스토그램 버킷이 있어야 값이 나온다.

## 9. 미검증

- GitHub Actions 실제 실행(`workflow_run` 조건, environment 승인, gha 캐시), OIDC 역할 assume, ECR push·immutable 태그 충돌 처리, `describe-images` 결과.
- 이미지 빌드(로컬 Docker 데몬 없음), 웹 번들 `scratch` 이미지 `docker create`/`cp` 실제 동작.
- SSM 전용 문서 생성·`allowedPattern`(Java 정규식) 실제 적용, `{{ }}` 치환·`sh` 실행, `timeoutSeconds` 파라미터 치환, `send-command` 권한이 문서 ARN으로 제한되는지, `ssm:CancelCommand`가 인스턴스 ARN 리소스 수준 권한을 지원하는지(지원하지 않으면 취소가 거부되고 문서의 `executionTimeout`이 실행을 끝낸다), `get-command-invocation` 출력 잘림(약 24,000자 [추정]), 취소·시간 초과 시 deploy.sh가 받는 신호.
- EC2에서 `deploy.sh` 실제 실행, nginx 설정 문법(`nginx -t` 미실행), reload 중 연결 유지, TLS.
- 프록시 뒤 IP 판정: Docker 포트 publish로 들어온 요청의 원격 주소(브리지 게이트웨이 172.x)가 Tomcat 기본 internal proxies 정규식에 포함돼 `X-Forwarded-For`가 적용된다고 본다 [추정].
- prometheus `v3.5.0`·grafana `12.1.1` 이미지 태그 존재, compose 플러그인 설치 여부.
- 첫 배포 시 MySQL 대여소 데이터 존재.

## 10. 남은 위험

- forwarded 설정 오류 → 대량 429(체크리스트 2).
- 단일 인스턴스: 호스트 장애·재부팅 시 전체 중단. 정지된 이전 색은 `unless-stopped`라 재부팅 후에도 꺼진 채로 남는다(의도).
- readiness가 실패하면 그 색에 남아 있던 정지 컨테이너는 이미 교체돼 사라진다. 롤백은 ECR digest로 수행한다.
- 비밀은 `docker inspect`로 root에게 보인다. 호스트 root 접근은 SSM 권한으로만 통제.
- 배포 중 두 색이 동시에 떠 메모리 사용이 일시적으로 두 배가 된다. 인스턴스 크기와 `JDK_JAVA_OPTIONS`(MaxRAMPercentage)를 맞춰야 한다.
- 웹·API 동시 전환이 아니다: 백엔드 전환 후 웹 전환 사이 수 초 동안 새 API + 이전 웹 조합이 가능하다. API 하위 호환을 한 릴리스 유지하는 규칙(`/routes/plan` 병행)에 의존한다.
- 액션은 SHA 고정이지만 갱신 자동화(Dependabot 등)는 없다.
- `deploy.sh`를 바꾼 커밋은 관리자의 호스트 스크립트 갱신 없이는 배포되지 않는다(의도된 거부, exit 3). 갱신을 잊으면 배포가 멈춘다.
- 롤백 로그 마스킹은 SSM 값과 일반적인 키 형태만 가린다. 다른 형태의 민감 정보(예: 사용자 입력이 로그에 찍힌 경우)는 가리지 못한다 → 공개 저장소는 `ROLLBACK_LOG_LINES=0`.
- 점검 켜기·끄기도 컨테이너 재생성이라 readiness(카탈로그 적재) 시간만큼 걸리고, 실패하면 이전 점검 상태가 유지된다.
- SIGKILL·호스트 재부팅 중 배포는 롤백되지 않는다(수동 복구).
