# EC2 1회 준비 (bootstrap)

배포 자동화(`.github/workflows/deploy.yml` → SSM → `deploy/ec2/deploy.sh`)가 동작하기 전에 **한 번** 수행한다.
전체 흐름·사용자 제공 값·체크리스트는 [docs/deploy-ec2.md](../../docs/deploy-ec2.md).
표기: **[사실]** 이 저장소 코드로 확인 / **[추정]** 설계 판단·일반 지식 / **[미검증]** 실제 AWS에서 실행 확인 안 함.
아래 명령은 모두 **[미검증]**이다. `<...>`는 사용자 값으로 바꾼다.

## 0. 전제 [추정]

- OS: Amazon Linux 2023(SSM Agent·AWS CLI v2 기본 포함). 다른 배포판이면 패키지 명령만 바꾼다.
- 보안 그룹 인바운드: 80, 443만. **22번은 열지 않는다**(접속·배포 모두 SSM).
- 아키텍처: 워크플로 기본 이미지 플랫폼이 `linux/amd64`다. Graviton(arm64)이면 GitHub `vars.IMAGE_PLATFORM=linux/arm64`와 빌드 방식(QEMU 또는 arm 러너)을 함께 정해야 한다.

## 1. IAM instance profile

인스턴스 역할에 다음 두 가지를 붙인다.

1. AWS 관리형 정책 `AmazonSSMManagedInstanceCore` (Run Command·Session Manager 수신)
2. 인라인 정책 [`deploy/aws/ec2-instance-policy.json`](../aws/ec2-instance-policy.json): ECR **pull 전용**, `/ddareungi/production/backend` 경로 SSM 파라미터 읽기

SecureString을 기본 키(`alias/aws/ssm`)로 암호화하면 별도 `kms:Decrypt` 문은 필요 없다고 본다 [추정]. 고객 관리형 KMS 키를 쓰면 그 키에 대한 `kms:Decrypt`를 추가한다.

## 2. 패키지와 Docker 로그 회전

```bash
sudo dnf install -y docker nginx
sudo install -d -m 0755 /etc/docker
cat <<'EOF' | sudo tee /etc/docker/daemon.json
{ "log-driver": "json-file", "log-opts": { "max-size": "20m", "max-file": "5" } }
EOF
sudo systemctl enable --now docker nginx
command -v flock curl aws   # util-linux, curl, AWS CLI v2
```

`deploy.sh`는 root로 실행되며(`AWS-RunShellScript`) `docker`, `nginx`, `curl`, `aws`, `flock`, GNU `mv -T`를 사용한다.

## 3. 디렉터리와 설정 파일

```bash
sudo install -d -m 0700 /etc/ddareungi
sudo install -d -m 0755 /etc/nginx/ddareungi /srv/ddareungi/web /var/lib/ddareungi/deploy /opt/ddareungi/bin
# 저장소에서 복사(로컬 PC에서 SSM 세션으로 붙여넣거나 S3 등 원하는 방식으로 전달)
sudo install -m 0600 backend.env.example /etc/ddareungi/backend.env     # 값 채우기
sudo install -m 0600 deploy.env.example  /etc/ddareungi/deploy.env      # AWS_REGION 등
sudo install -m 0644 nginx/locations.conf        /etc/nginx/ddareungi/locations.conf
sudo install -m 0644 nginx/upstream-backend.conf /etc/nginx/ddareungi/upstream-backend.conf
sudo install -m 0644 nginx/ddareungi.conf        /etc/nginx/conf.d/ddareungi.conf
sudo sed -i 's/<DOMAIN>/<실제 도메인>/' /etc/nginx/conf.d/ddareungi.conf
```

- `/etc/nginx/nginx.conf`의 기본 `server { listen 80 ... }` 블록이 있으면 주석 처리한다(같은 포트의 default server 충돌 방지).
- 첫 배포 전에는 웹 번들이 없어 `/`가 404이고 `/api`는 502다. 정상이다.
- `sudo nginx -t && sudo systemctl reload nginx`

`deploy.sh`(`/opt/ddareungi/bin/deploy.sh`)는 워크플로가 **매 배포마다 SSM 명령에 실어 설치**하므로 직접 복사할 필요가 없다(sha256 검증 후 교체).

## 4. 런타임 비밀 (SSM Parameter Store SecureString)

파라미터 이름의 마지막 부분이 그대로 환경 변수 이름이 된다. 값은 **한 줄**이어야 한다.

```bash
read -rs V && aws ssm put-parameter --type SecureString --name /ddareungi/production/backend/MYSQL_PASSWORD --value "$V" && unset V
read -rs V && aws ssm put-parameter --type SecureString --name /ddareungi/production/backend/KAKAO_MAPS_REST_API_KEY --value "$V" && unset V
read -rs V && aws ssm put-parameter --type SecureString --name /ddareungi/production/backend/SEOUL_API_KEY --value "$V" && unset V
# 필요 시: ORS_API_KEY, SPRING_DATA_REDIS_PASSWORD
```

`deploy.sh`는 이 값을 `/run/ddareungi/`의 0600 임시 파일로 받아 `docker run --env-file`에 넘기고 즉시 지운다. 컨테이너 설정(`docker inspect`)에는 남으므로 호스트 root 접근 자체를 SSM으로 통제한다.

## 5. ECR 저장소 (백엔드·웹 번들)

```bash
for repo in <ECR_BACKEND_REPOSITORY> <ECR_WEB_REPOSITORY>; do
  aws ecr create-repository --repository-name "$repo" --image-tag-mutability IMMUTABLE \
    --image-scanning-configuration scanOnPush=true
  aws ecr put-lifecycle-policy --repository-name "$repo" \
    --lifecycle-policy-text file://deploy/aws/ecr-lifecycle-policy.json
done
```

태그는 커밋 SHA만, `latest`는 쓰지 않는다. 최근 20개만 남으므로 그보다 오래된 SHA로는 롤백할 수 없다.

## 6. GitHub OIDC 역할

```bash
# 계정에 GitHub OIDC provider가 없을 때만
aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com
# 템플릿의 <ACCOUNT_ID> 등을 바꾼 사본으로 실행
aws iam create-role --role-name <GITHUB_DEPLOY_ROLE_NAME> \
  --assume-role-policy-document file://github-oidc-trust-policy.json
aws iam put-role-policy --role-name <GITHUB_DEPLOY_ROLE_NAME> --policy-name deploy \
  --policy-document file://github-deploy-role-policy.json
```

신뢰 정책은 `sub = repo:mnjihwan/Ddareungi-rider:environment:production`만 허용한다. 즉 `environment: production` 잡만 역할을 맡을 수 있다.
create-open-id-connect-provider의 thumbprint 요구 여부는 CLI 버전에 따라 다를 수 있다 [미검증].

## 7. GitHub Environment `production`

- Settings → Environments → `production`: Required reviewers(승인자), Deployment branches = `main`만.
- Environment variables: `AWS_REGION`, `AWS_DEPLOY_ROLE_ARN`, `ECR_BACKEND_REPOSITORY`, `ECR_WEB_REPOSITORY`, `EC2_INSTANCE_ID`, `KAKAO_MAPS_JAVASCRIPT_KEY`, 선택 `IMAGE_PLATFORM`.
- 장기 AWS 키(Access Key)는 저장하지 않는다.

## 8. MySQL·Redis 연결

백엔드 컨테이너는 Docker 네트워크 `ddareungi`(최초 배포 시 `deploy.sh`가 생성)에 붙는다.
- RDS/ElastiCache 등 외부: `backend.env`의 `MYSQL_URL`/`REDIS_HOST`에 엔드포인트를 넣고 보안 그룹에서 이 인스턴스만 허용.
- 같은 호스트의 컨테이너: 해당 컨테이너를 `ddareungi` 네트워크에 연결(`docker network connect ddareungi <mysql 컨테이너>`)하고 컨테이너 이름으로 접근.
- **MySQL에 대여소 데이터가 있어야** readiness(`stationCatalog`)가 UP이 된다 [사실: `application-infra.yml` readiness 그룹]. 없으면 배포는 readiness 실패로 중단되고 기존 버전이 유지된다.

## 9. 관측(선택)

```bash
cd deploy/observability     # 호스트에 복사한 위치
echo 'GRAFANA_ADMIN_PASSWORD=<강한 암호>' | sudo tee /etc/ddareungi/observability.env >/dev/null
sudo chmod 600 /etc/ddareungi/observability.env
sudo docker compose --env-file /etc/ddareungi/observability.env up -d   # ddareungi 네트워크가 먼저 있어야 함
```

Amazon Linux 2023의 `docker` 패키지에는 compose 플러그인이 없을 수 있다 [추정]. 없으면 Docker 공식 문서 방식으로 플러그인을 설치한다.
`/actuator/prometheus`는 O8 병합 후에만 노출된다. 그 전에는 scrape 대상이 404로 down이다.
