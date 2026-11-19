#!/usr/bin/env bash
# Blue/green deploy of the ddareungrider backend (and optionally the static web bundle) on one EC2 host.
#
# Usage:
#   deploy.sh [--force-restart] <backend-image@sha256:...> [<web-image@sha256:...>]
#   deploy.sh maintenance on|off
#
#   --force-restart   recreate the backend on the other color even when the digest is already active
#                     (picks up a changed backend.env / SSM secret; same blue/green steps and rollback)
#   maintenance on|off  set CLIENT_WEB_MAINTENANCE in $BACKEND_ENV_FILE and force-restart the active image
#                       (the env file is restored if the restart fails)
#
# Invoked by .github/workflows/deploy.yml through SSM Run Command with the dedicated Command document
# deploy/aws/ssm-document-deploy.json (runs as root). Images are always referenced by digest.
#
# Flow (any failure before "commit" restores the previous state and exits non-zero; INT/TERM/HUP too):
#   1. ECR login, pull by digest (backend; web bundle extracted to a new release directory)
#   2. active color = the color the nginx upstream file points at, if that container is running
#   3. new container on the other color's localhost port (management port 8090 not published)
#   4. readiness: docker exec curl http://localhost:8090/actuator/health/readiness until timeout
#      -> failure: remove the new container, keep the old one serving, exit 1
#   5. direct smoke of the NEW color on its own port (before nginx changes):
#      GET /api/client-config?platform=web -> 200 and "maintenance" == expected value from the env files;
#      unless in maintenance: GET /api/v1/routes/status -> 200 and "configured":true
#      -> failure: remove the new container, exit 1
#   6. atomically replace the upstream file (mv), nginx -t && nginx -s reload
#   7. the same smoke through nginx (127.0.0.1:18080)
#      -> failure: restore the previous upstream file, reload, remove the new container, exit 1
#   8. web: switch the `current` symlink atomically, smoke GET / == 200 (failure: revert web and backend)
#   9. stage state files (failure -> rollback), commit, drain then stop the old container (kept, stopped)
#
# Idempotent: re-running with the digest that is already active changes nothing (unless --force-restart).
# Configuration: environment variables, optionally from $DEPLOY_CONFIG (default /etc/ddareungi/deploy.env).
set -euo pipefail
umask 022

log() { printf '%s [deploy] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
usage() {
  echo "usage: $0 [--force-restart] <registry/repo@sha256:<64 hex>> [<registry/repo@sha256:<64 hex>>]" >&2
  echo "       $0 maintenance on|off" >&2
  exit 2
}

MODE=deploy; FORCE_RESTART=0; MAINTENANCE_TARGET=""; BACKEND_REF=""; WEB_REF=""
case ${1:-} in
  maintenance)
    [[ $# -eq 2 && ( $2 == on || $2 == off ) ]] || usage
    MODE=maintenance; MAINTENANCE_TARGET=$2; FORCE_RESTART=1 ;;
  --force-restart)
    shift; FORCE_RESTART=1
    [[ $# -ge 1 && $# -le 2 ]] || usage
    BACKEND_REF=$1; WEB_REF=${2:-} ;;
  *)
    [[ $# -ge 1 && $# -le 2 ]] || usage
    BACKEND_REF=$1; WEB_REF=${2:-} ;;
esac

DEPLOY_CONFIG=${DEPLOY_CONFIG:-/etc/ddareungi/deploy.env}
if [[ -f $DEPLOY_CONFIG ]]; then
  # shellcheck disable=SC1090
  source "$DEPLOY_CONFIG"
fi

APP_NAME=${APP_NAME:-ddareungi}
BACKEND_ENV_FILE=${BACKEND_ENV_FILE:-/etc/ddareungi/backend.env}
SSM_PARAMETER_PATH=${SSM_PARAMETER_PATH:-}
DOCKER_NETWORK=${DOCKER_NETWORK:-ddareungi}
BLUE_PORT=${BLUE_PORT:-18081}
GREEN_PORT=${GREEN_PORT:-18082}
MANAGEMENT_PORT=${MANAGEMENT_PORT:-8090}
NGINX_BIN=${NGINX_BIN:-nginx}
NGINX_UPSTREAM_FILE=${NGINX_UPSTREAM_FILE:-/etc/nginx/ddareungi/upstream-backend.conf}
SMOKE_BASE_URL=${SMOKE_BASE_URL:-http://127.0.0.1:18080}          # nginx loopback smoke server
SMOKE_STATUS_PATH=${SMOKE_STATUS_PATH:-/api/v1/routes/status}
SMOKE_CLIENT_CONFIG_PATH=${SMOKE_CLIENT_CONFIG_PATH:-/api/client-config?platform=web}
SMOKE_REQUIRE_CONFIGURED=${SMOKE_REQUIRE_CONFIGURED:-1}           # routes/status must answer "configured":true
WEB_SMOKE_URL=${WEB_SMOKE_URL:-http://127.0.0.1:18080/}
READINESS_TIMEOUT=${READINESS_TIMEOUT:-240}
READINESS_INTERVAL=${READINESS_INTERVAL:-5}
SMOKE_ATTEMPTS=${SMOKE_ATTEMPTS:-5}
SMOKE_INTERVAL=${SMOKE_INTERVAL:-2}
DRAIN_SECONDS=${DRAIN_SECONDS:-30}
STOP_TIMEOUT=${STOP_TIMEOUT:-30}
STATE_DIR=${STATE_DIR:-/var/lib/ddareungi/deploy}
WEB_ROOT=${WEB_ROOT:-/srv/ddareungi/web}
WEB_KEEP_RELEASES=${WEB_KEEP_RELEASES:-5}
SECRETS_DIR=${SECRETS_DIR:-/run/ddareungi}
LOCK_FILE=${LOCK_FILE:-/run/lock/ddareungi-deploy.lock}
# Lines of the failed container's log printed on rollback (they reach the GitHub Actions log through SSM).
# Values loaded from SSM and key=value pairs that look like credentials are masked. 0 = do not print.
ROLLBACK_LOG_LINES=${ROLLBACK_LOG_LINES:-30}

REF_PATTERN='^[a-z0-9.-]+(:[0-9]+)?/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$'
if [[ $MODE == deploy ]]; then
  [[ $BACKEND_REF =~ $REF_PATTERN ]] || { log "backend image must be <registry>/<repo>@sha256:<digest>: $BACKEND_REF"; usage; }
  if [[ -n $WEB_REF && ! $WEB_REF =~ $REF_PATTERN ]]; then
    log "web image must be <registry>/<repo>@sha256:<digest>: $WEB_REF"; usage
  fi
fi
[[ $ROLLBACK_LOG_LINES =~ ^[0-9]+$ ]] || die "ROLLBACK_LOG_LINES must be a number"
[[ -f $BACKEND_ENV_FILE ]] || die "backend env file missing: $BACKEND_ENV_FILE (see deploy/ec2/backend.env.example)"

mkdir -p "$STATE_DIR" "$(dirname "$LOCK_FILE")" "$(dirname "$NGINX_UPSTREAM_FILE")"
exec 9>"$LOCK_FILE"
if command -v flock >/dev/null 2>&1; then
  flock -n 9 || die "another deploy is running (lock $LOCK_FILE)"
fi

# ---------- rollback bookkeeping (used by the EXIT trap) ----------
NEW_CONTAINER=""          # container started by this run, removed on failure
UPSTREAM_SWITCHED=0       # upstream file replaced by this run
UPSTREAM_BACKUP=""        # copy of the previous upstream file ("" = there was none)
WEB_SWITCHED=0
WEB_PREV_TARGET=""
ENV_CHANGED=0             # backend env file edited by `maintenance on|off`
ENV_BACKUP=""
SECRETS_FILE=""
SECRET_VALUES=()          # SSM values kept in memory only, used to mask the rollback log
COMMITTED=0

nginx_reload() { "$NGINX_BIN" -t && "$NGINX_BIN" -s reload; }

mask_stream() {
  # Replace every loaded SSM value, then anything shaped like `password=...`, `token: ...`, `Bearer ...`.
  local line v
  while IFS= read -r line || [[ -n $line ]]; do
    for v in ${SECRET_VALUES[@]+"${SECRET_VALUES[@]}"}; do
      [[ ${#v} -ge 4 ]] && line=${line//"$v"/***}
    done
    printf '%s\n' "$line"
  done | sed -E \
    -e 's/((password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|authorization|credential)[A-Za-z0-9_.-]*["]?[[:space:]]*[=:][[:space:]]*)("[^"]*"|(bearer|basic)[[:space:]]+[^[:space:],;"]+|[^[:space:],;&"]+)/\1***/Ig' \
    -e 's/(bearer[[:space:]]+)[^[:space:],;"]+/\1***/Ig'
}

rollback() {
  set +e
  log "rolling back this run"
  if [[ $WEB_SWITCHED -eq 1 ]]; then
    if [[ -n $WEB_PREV_TARGET ]]; then
      ln -sfn "$WEB_PREV_TARGET" "$WEB_ROOT/current.rollback" && mv -Tf "$WEB_ROOT/current.rollback" "$WEB_ROOT/current"
      log "web: current -> $WEB_PREV_TARGET restored"
    else
      rm -f "$WEB_ROOT/current"; log "web: no previous release, current removed"
    fi
  fi
  if [[ $UPSTREAM_SWITCHED -eq 1 ]]; then
    if [[ -n $UPSTREAM_BACKUP ]]; then
      cp -f "$UPSTREAM_BACKUP" "$NGINX_UPSTREAM_FILE.rollback.$$" && mv -f "$NGINX_UPSTREAM_FILE.rollback.$$" "$NGINX_UPSTREAM_FILE"
      if nginx_reload; then log "nginx: previous upstream restored and reloaded"
      else log "nginx: RESTORE RELOAD FAILED - manual check required"; fi
    else
      log "nginx: first deploy, no previous upstream to restore"
    fi
  fi
  if [[ $ENV_CHANGED -eq 1 && -n $ENV_BACKUP ]]; then
    if cp -p "$ENV_BACKUP" "$BACKEND_ENV_FILE.rollback.$$" && mv -f "$BACKEND_ENV_FILE.rollback.$$" "$BACKEND_ENV_FILE"; then
      log "env: $BACKEND_ENV_FILE restored"
    else
      log "env: RESTORE OF $BACKEND_ENV_FILE FAILED - copy $ENV_BACKUP back by hand"
    fi
  fi
  if [[ -n $NEW_CONTAINER ]]; then
    if [[ $ROLLBACK_LOG_LINES -gt 0 ]]; then
      log "last $ROLLBACK_LOG_LINES log lines of $NEW_CONTAINER (masked):"
      docker logs --tail "$ROLLBACK_LOG_LINES" "$NEW_CONTAINER" 2>&1 | mask_stream >&2
    else
      log "container log output disabled (ROLLBACK_LOG_LINES=0); see docker logs on the host before the next deploy"
    fi
    docker rm -f "$NEW_CONTAINER" >/dev/null 2>&1 && log "removed new container $NEW_CONTAINER"
  fi
}

on_exit() {
  local rc=$?
  trap - EXIT
  trap '' INT TERM HUP     # a second signal must not cut the rollback short
  if [[ $rc -ne 0 && $COMMITTED -eq 0 ]]; then rollback; fi
  [[ -n $SECRETS_FILE ]] && rm -f "$SECRETS_FILE"
  rm -f "$STATE_DIR/backend.current.new" "$STATE_DIR/web.current.new" 2>/dev/null
  if [[ $rc -eq 0 ]]; then log "done"
  elif [[ $COMMITTED -eq 1 ]]; then log "FAILED after commit (exit $rc); the new version is serving"
  else log "FAILED (exit $rc); previous version keeps serving"; fi
  exit "$rc"
}
trap on_exit EXIT
# Signals (SSM cancel, Ctrl-C) leave through the EXIT trap, which rolls back an uncommitted run.
# SIGKILL cannot be trapped; see docs/deploy-ec2.md (manual recovery).
trap 'log "received SIGINT"; exit 130' INT
trap 'log "received SIGTERM"; exit 143' TERM
trap 'log "received SIGHUP"; exit 129' HUP

# ---------- helpers ----------
port_of() { if [[ $1 == blue ]]; then echo "$BLUE_PORT"; else echo "$GREEN_PORT"; fi; }
other_color() { if [[ $1 == blue ]]; then echo green; else echo blue; fi; }
container_name() { echo "${APP_NAME}-backend-$1"; }
container_running() { [[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" == true ]]; }
container_image() { docker inspect -f '{{.Config.Image}}' "$1" 2>/dev/null || true; }

upstream_color() {
  [[ -f $NGINX_UPSTREAM_FILE ]] || return 0
  if grep -q "127\.0\.0\.1:$BLUE_PORT\b" "$NGINX_UPSTREAM_FILE"; then echo blue
  elif grep -q "127\.0\.0\.1:$GREEN_PORT\b" "$NGINX_UPSTREAM_FILE"; then echo green; fi
}

write_upstream() {
  local port=$1 tmp="$NGINX_UPSTREAM_FILE.tmp.$$"
  cat >"$tmp" <<EOF
# Managed by deploy/ec2/deploy.sh - replaced atomically on every deploy. Do not edit by hand.
upstream ddareungi_backend {
    server 127.0.0.1:$port max_fails=0;
    keepalive 16;
}
EOF
  mv -f "$tmp" "$NGINX_UPSTREAM_FILE"
}

http_code() { curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "$1" 2>/dev/null || true; }

# GET $1 -> HTTP_CODE, HTTP_BODY (body is not logged).
http_get() {
  local out
  out=$(curl -sS --max-time 5 -w '\n%{http_code}' "$1" 2>/dev/null || true)
  HTTP_CODE=${out##*$'\n'}
  if [[ $out == *$'\n'* ]]; then HTTP_BODY=${out%$'\n'*}; else HTTP_BODY=""; fi
}

smoke() {
  local url=$1 i code=""
  for ((i = 1; i <= SMOKE_ATTEMPTS; i++)); do
    code=$(http_code "$url")
    if [[ $code == 200 ]]; then log "smoke ok: $url ($i/$SMOKE_ATTEMPTS)"; return 0; fi
    log "smoke $url -> ${code:-no response} ($i/$SMOKE_ATTEMPTS)"
    ((i < SMOKE_ATTEMPTS)) && sleep "$SMOKE_INTERVAL"
  done
  return 1
}

# One backend check against base URL $1. Sets SMOKE_REASON on failure.
backend_ok() {
  local base=$1 re_maint re_conf
  re_maint="\"maintenance\"[[:space:]]*:[[:space:]]*$EXPECT_MAINTENANCE([^a-z]|$)"
  re_conf='"configured"[[:space:]]*:[[:space:]]*true([^a-z]|$)'
  http_get "$base$SMOKE_CLIENT_CONFIG_PATH"
  [[ $HTTP_CODE == 200 ]] || { SMOKE_REASON="client-config -> ${HTTP_CODE:-no response}"; return 1; }
  [[ $HTTP_BODY =~ $re_maint ]] || { SMOKE_REASON="client-config maintenance is not $EXPECT_MAINTENANCE"; return 1; }
  [[ $EXPECT_MAINTENANCE == true ]] && return 0   # /api/v1/** answers 503 MAINTENANCE_MODE by design
  http_get "$base$SMOKE_STATUS_PATH"
  [[ $HTTP_CODE == 200 ]] || { SMOKE_REASON="routes/status -> ${HTTP_CODE:-no response}"; return 1; }
  if [[ $SMOKE_REQUIRE_CONFIGURED == 1 && ! $HTTP_BODY =~ $re_conf ]]; then
    SMOKE_REASON='routes/status is not "configured":true (routing provider key/setting missing?)'; return 1
  fi
}

smoke_backend() {
  local base=$1 label=$2 i
  for ((i = 1; i <= SMOKE_ATTEMPTS; i++)); do
    SMOKE_REASON=""
    if backend_ok "$base"; then log "smoke ok ($label): $base maintenance=$EXPECT_MAINTENANCE ($i/$SMOKE_ATTEMPTS)"; return 0; fi
    log "smoke ($label) $base: $SMOKE_REASON ($i/$SMOKE_ATTEMPTS)"
    ((i < SMOKE_ATTEMPTS)) && sleep "$SMOKE_INTERVAL"
  done
  return 1
}

# Last KEY=value of $2 in env file $1 ("" when absent). docker --env-file: no quotes, later lines win.
env_value() { [[ -f $1 ]] || return 0; sed -n "s/^$2=//p" "$1" | tail -n 1; }
truthy() { [[ ${1,,} == true || ${1,,} == on || ${1,,} == yes || $1 == 1 ]]; }

# Replace (or append) KEY=value in the env file, atomically, keeping owner and mode.
set_env_value() {
  local file=$1 key=$2 value=$3 tmp="$1.tmp.$$"
  cp -p "$file" "$tmp"
  awk -v k="$key" -v v="$value" 'BEGIN { done = 0 }
    index($0, k "=") == 1 { if (!done) { print k "=" v; done = 1 }; next }
    { print }
    END { if (!done) print k "=" v }' "$file" >"$tmp"
  mv -f "$tmp" "$file"
}

ecr_login() {
  local registry=${1%%/*} region=${AWS_REGION:-}
  if [[ -z $region && $registry =~ \.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com$ ]]; then region=${BASH_REMATCH[1]}; fi
  [[ -n $region ]] || die "AWS_REGION is not set and cannot be derived from $registry"
  aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "$registry" >/dev/null
  log "ecr login ok: $registry"
}

load_secrets() {
  # SSM Parameter Store SecureString -> env file readable by root only. Parameter name basename = env name.
  # Values must be single-line (the text output is tab/line based). A failed lookup or an empty path aborts:
  # starting the backend without its secrets would only fail later (or worse, start half-configured).
  [[ -n $SSM_PARAMETER_PATH ]] || return 0
  local region=${AWS_REGION:-} out name value count=0
  [[ -n $region ]] || region=$(sed -nE 's#^[^/]*\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com/.*#\1#p' <<<"$BACKEND_REF")
  [[ -n $region ]] || die "AWS_REGION is not set and cannot be derived from $BACKEND_REF"
  out=$(aws ssm get-parameters-by-path --region "$region" --path "$SSM_PARAMETER_PATH" --recursive \
    --with-decryption --query 'Parameters[].[Name,Value]' --output text) \
    || die "SSM lookup of $SSM_PARAMETER_PATH failed (instance role ssm:GetParametersByPath / kms:Decrypt?)"
  mkdir -p "$SECRETS_DIR"; chmod 700 "$SECRETS_DIR"
  SECRETS_FILE="$SECRETS_DIR/backend.$$.env"
  (umask 077; : >"$SECRETS_FILE")
  while IFS=$'\t' read -r name value; do
    [[ -n $name ]] || continue
    name=${name##*/}
    [[ $name =~ ^[A-Z_][A-Z0-9_]*$ ]] || die "SSM parameter name is not a valid env name: $name"
    printf '%s=%s\n' "$name" "$value" >>"$SECRETS_FILE"
    SECRET_VALUES+=("$value")
    count=$((count + 1))
  done <<<"$out"
  [[ $count -gt 0 ]] || die "SSM path $SSM_PARAMETER_PATH returned no parameters (wrong path or region?)"
  log "loaded $count secret(s) from SSM path $SSM_PARAMETER_PATH (names only logged)"
}

prepare_web() {
  # Extract /site from the web bundle image into releases/<digest prefix>. Sets WEB_RELEASE.
  local digest=${WEB_REF##*@sha256:} tmp cid
  WEB_RELEASE="releases/${digest:0:16}"
  if [[ -f $WEB_ROOT/$WEB_RELEASE/index.html ]]; then log "web: release $WEB_RELEASE already extracted"; return 0; fi
  mkdir -p "$WEB_ROOT/releases"
  tmp="$WEB_ROOT/$WEB_RELEASE.tmp.$$"
  rm -rf "$tmp"; mkdir -p "$tmp"
  cid="${APP_NAME}-web-extract-$$"
  docker create --name "$cid" "$WEB_REF" /noop >/dev/null
  if ! docker cp "$cid:/site/." "$tmp/"; then docker rm -f "$cid" >/dev/null 2>&1 || true; rm -rf "$tmp"; die "web: docker cp failed"; fi
  docker rm -f "$cid" >/dev/null
  [[ -f $tmp/index.html ]] || { rm -rf "$tmp"; die "web: bundle has no index.html"; }
  chmod -R a+rX "$tmp"
  rm -rf "${WEB_ROOT:?}/$WEB_RELEASE"; mv "$tmp" "$WEB_ROOT/$WEB_RELEASE"
  log "web: extracted $WEB_RELEASE"
}

# ---------- 1. determine colors ----------
ACTIVE=$(upstream_color)
if [[ -n $ACTIVE ]] && ! container_running "$(container_name "$ACTIVE")"; then
  log "upstream points at $ACTIVE but its container is not running; treating as no active backend"
  ACTIVE=""
fi
ACTIVE_NAME=""; ACTIVE_REF=""
if [[ -n $ACTIVE ]]; then ACTIVE_NAME=$(container_name "$ACTIVE"); ACTIVE_REF=$(container_image "$ACTIVE_NAME"); fi
log "active backend: ${ACTIVE:-none} ${ACTIVE_REF:+($ACTIVE_REF)}"

if [[ $MODE == maintenance ]]; then
  [[ -n $ACTIVE ]] || die "maintenance $MAINTENANCE_TARGET needs a running backend (deploy an image first)"
  [[ $ACTIVE_REF =~ $REF_PATTERN ]] || die "active container image is not a digest reference: $ACTIVE_REF"
  BACKEND_REF=$ACTIVE_REF
  log "maintenance $MAINTENANCE_TARGET: restarting the active image with CLIENT_WEB_MAINTENANCE updated"
fi

BACKEND_CHANGE=1
if [[ -n $ACTIVE && $ACTIVE_REF == "$BACKEND_REF" ]]; then
  if [[ $FORCE_RESTART -eq 1 ]]; then
    log "backend $BACKEND_REF is already active on $ACTIVE; restarting it on the other color (force)"
  else
    BACKEND_CHANGE=0
    log "backend $BACKEND_REF is already active on $ACTIVE; backend step skipped"
  fi
fi

WEB_CHANGE=0; WEB_RELEASE=""
if [[ -n $WEB_REF ]]; then
  WEB_CURRENT=$(readlink "$WEB_ROOT/current" 2>/dev/null || true)
  digest=${WEB_REF##*@sha256:}
  if [[ $WEB_CURRENT == "releases/${digest:0:16}" ]]; then log "web $WEB_REF is already current; web step skipped"
  else WEB_CHANGE=1; fi
fi

if [[ $BACKEND_CHANGE -eq 0 && $WEB_CHANGE -eq 0 ]]; then
  COMMITTED=1
  log "nothing to do"
  exit 0
fi

# ---------- 2. pull (an image used by the running container is already local) ----------
NEED_PULL=0
[[ $BACKEND_CHANGE -eq 1 && $BACKEND_REF != "$ACTIVE_REF" ]] && NEED_PULL=1
if [[ $NEED_PULL -eq 1 || $WEB_CHANGE -eq 1 ]]; then ecr_login "${BACKEND_REF}"; fi
if [[ $WEB_CHANGE -eq 1 && ${WEB_REF%%/*} != "${BACKEND_REF%%/*}" ]]; then ecr_login "$WEB_REF"; fi
if [[ $NEED_PULL -eq 1 ]]; then docker pull -q "$BACKEND_REF" >/dev/null; log "pulled $BACKEND_REF"; fi
if [[ $WEB_CHANGE -eq 1 ]]; then docker pull -q "$WEB_REF" >/dev/null; log "pulled $WEB_REF"; prepare_web; fi

# ---------- 3. start the new color and wait for readiness ----------
EXPECT_MAINTENANCE=false
if [[ $BACKEND_CHANGE -eq 1 ]]; then
  TARGET=$(other_color "${ACTIVE:-green}")   # first deploy -> blue
  TARGET_NAME=$(container_name "$TARGET")
  TARGET_PORT=$(port_of "$TARGET")
  docker network inspect "$DOCKER_NETWORK" >/dev/null 2>&1 || docker network create "$DOCKER_NETWORK" >/dev/null

  if [[ $MODE == maintenance ]]; then
    ENV_BACKUP="$STATE_DIR/backend.env.prev"
    cp -p "$BACKEND_ENV_FILE" "$ENV_BACKUP"
    ENV_CHANGED=1
    if [[ $MAINTENANCE_TARGET == on ]]; then set_env_value "$BACKEND_ENV_FILE" CLIENT_WEB_MAINTENANCE true
    else set_env_value "$BACKEND_ENV_FILE" CLIENT_WEB_MAINTENANCE false; fi
    log "env: CLIENT_WEB_MAINTENANCE set for '$MAINTENANCE_TARGET' in $BACKEND_ENV_FILE (backup $ENV_BACKUP)"
  fi

  load_secrets
  # Effective maintenance flag of the new container: backend.env, overridden by an SSM parameter of the same name.
  maint_raw=$(env_value "$BACKEND_ENV_FILE" CLIENT_WEB_MAINTENANCE)
  if [[ -n $SECRETS_FILE && -n $(env_value "$SECRETS_FILE" CLIENT_WEB_MAINTENANCE) ]]; then
    maint_raw=$(env_value "$SECRETS_FILE" CLIENT_WEB_MAINTENANCE)
    log "note: CLIENT_WEB_MAINTENANCE also comes from SSM and overrides backend.env"
  fi
  if truthy "$maint_raw"; then EXPECT_MAINTENANCE=true; fi
  if [[ $MODE == maintenance ]]; then
    want=false; [[ $MAINTENANCE_TARGET == on ]] && want=true
    [[ $EXPECT_MAINTENANCE == "$want" ]] || die "CLIENT_WEB_MAINTENANCE would still be $EXPECT_MAINTENANCE (SSM parameter overrides backend.env?)"
  fi

  if docker inspect "$TARGET_NAME" >/dev/null 2>&1; then
    log "removing previous $TARGET container ($(container_image "$TARGET_NAME"))"
    docker rm -f "$TARGET_NAME" >/dev/null
  fi
  run_args=(run -d --name "$TARGET_NAME" --network "$DOCKER_NETWORK" --restart unless-stopped
    -p "127.0.0.1:$TARGET_PORT:8080"
    --env-file "$BACKEND_ENV_FILE")
  [[ -n $SECRETS_FILE ]] && run_args+=(--env-file "$SECRETS_FILE")
  # Forced here so that a wrong env file cannot publish actuator or disable the proxy client-IP handling.
  run_args+=(-e "MANAGEMENT_SERVER_PORT=$MANAGEMENT_PORT" -e "SERVER_FORWARD_HEADERS_STRATEGY=native"
    --label "ddareungi.color=$TARGET" --label "ddareungi.role=backend" "$BACKEND_REF")
  NEW_CONTAINER=$TARGET_NAME   # set first: a failed `docker run` can still leave a created container behind
  docker "${run_args[@]}" >/dev/null
  [[ -n $SECRETS_FILE ]] && { rm -f "$SECRETS_FILE"; SECRETS_FILE=""; }
  log "started $TARGET_NAME on 127.0.0.1:$TARGET_PORT"

  deadline=$((SECONDS + READINESS_TIMEOUT))
  until docker exec "$TARGET_NAME" curl -fsS --max-time 3 -o /dev/null \
      "http://localhost:$MANAGEMENT_PORT/actuator/health/readiness" 2>/dev/null; do
    container_running "$TARGET_NAME" || die "$TARGET_NAME exited before becoming ready"
    ((SECONDS < deadline)) || die "$TARGET_NAME not ready within ${READINESS_TIMEOUT}s"
    sleep "$READINESS_INTERVAL"
  done
  log "$TARGET_NAME is ready"

  # ---------- 4. smoke the new color directly (proves the answer comes from $TARGET) ----------
  smoke_backend "http://127.0.0.1:$TARGET_PORT" "direct $TARGET" || die "direct smoke of $TARGET_NAME failed"

  # ---------- 5. switch nginx ----------
  if [[ -f $NGINX_UPSTREAM_FILE ]]; then
    UPSTREAM_BACKUP="$STATE_DIR/upstream-backend.conf.prev"
    cp -f "$NGINX_UPSTREAM_FILE" "$UPSTREAM_BACKUP"
  fi
  write_upstream "$TARGET_PORT"
  UPSTREAM_SWITCHED=1
  nginx_reload || die "nginx -t / reload failed with the new upstream"
  log "nginx upstream -> $TARGET (127.0.0.1:$TARGET_PORT)"

  # ---------- 6. smoke through the proxy ----------
  smoke_backend "$SMOKE_BASE_URL" "via nginx" || die "smoke through nginx failed after switching to $TARGET"
fi

# ---------- 7. web switch ----------
if [[ $WEB_CHANGE -eq 1 ]]; then
  WEB_PREV_TARGET=$(readlink "$WEB_ROOT/current" 2>/dev/null || true)
  ln -sfn "$WEB_RELEASE" "$WEB_ROOT/current.tmp.$$"
  mv -Tf "$WEB_ROOT/current.tmp.$$" "$WEB_ROOT/current"
  WEB_SWITCHED=1
  log "web: current -> $WEB_RELEASE"
  smoke "$WEB_SMOKE_URL" || die "web smoke failed"
fi

# ---------- 8. stage state (a write failure here still rolls back), then commit ----------
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
history=()
if [[ $BACKEND_CHANGE -eq 1 ]]; then
  printf '%s\n' "$BACKEND_REF" >"$STATE_DIR/backend.current.new" || die "cannot write $STATE_DIR/backend.current.new"
  what=deploy
  [[ $BACKEND_REF == "$ACTIVE_REF" ]] && what=restart
  [[ $MODE == maintenance ]] && what="maintenance-$MAINTENANCE_TARGET"
  history+=("$now backend $what $TARGET $BACKEND_REF (was ${ACTIVE_REF:-none}) maintenance=$EXPECT_MAINTENANCE")
fi
if [[ $WEB_CHANGE -eq 1 ]]; then
  printf '%s\n' "$WEB_REF" >"$STATE_DIR/web.current.new" || die "cannot write $STATE_DIR/web.current.new"
  history+=("$now web $WEB_RELEASE $WEB_REF (was ${WEB_PREV_TARGET:-none})")
fi

COMMITTED=1
STATE_WARN=0
promote_state() {
  # $1 = backend|web, $2 = 1 to rotate current -> previous
  if [[ $2 -eq 1 && -f $STATE_DIR/$1.current ]]; then cp -f "$STATE_DIR/$1.current" "$STATE_DIR/$1.previous" || return 1; fi
  mv -f "$STATE_DIR/$1.current.new" "$STATE_DIR/$1.current"
}
if [[ $BACKEND_CHANGE -eq 1 ]]; then
  rotate=1; [[ $BACKEND_REF == "$ACTIVE_REF" ]] && rotate=0
  promote_state backend "$rotate" || { STATE_WARN=1; log "WARN: backend state files not updated; $BACKEND_REF is serving"; }
fi
if [[ $WEB_CHANGE -eq 1 ]]; then
  promote_state web 1 || { STATE_WARN=1; log "WARN: web state files not updated; $WEB_RELEASE is serving"; }
fi
printf '%s\n' "${history[@]}" >>"$STATE_DIR/history.log" || { STATE_WARN=1; log "WARN: history.log not updated"; }
log "committed"
[[ $STATE_WARN -eq 1 ]] && log "WARN: fix $STATE_DIR by hand (current/previous are used only for bookkeeping)"
[[ $ENV_CHANGED -eq 1 ]] && log "maintenance is now ${MAINTENANCE_TARGET^^} (CLIENT_WEB_MAINTENANCE=$EXPECT_MAINTENANCE)"

# ---------- 9. drain and stop the old color (failures here only warn) ----------
if [[ $BACKEND_CHANGE -eq 1 && -n $ACTIVE_NAME ]]; then
  log "draining $ACTIVE_NAME for ${DRAIN_SECONDS}s"
  sleep "$DRAIN_SECONDS"
  if docker stop -t "$STOP_TIMEOUT" "$ACTIVE_NAME" >/dev/null; then
    log "stopped $ACTIVE_NAME (kept for manual inspection; replaced by the next deploy)"
  else
    log "WARN: could not stop $ACTIVE_NAME"
  fi
fi
if [[ $WEB_CHANGE -eq 1 ]]; then
  # Keep the newest releases plus whatever current/previous point at.
  keep_prev=$(basename "${WEB_PREV_TARGET:-none}")
  ls -1t "$WEB_ROOT/releases" 2>/dev/null | grep -v '\.tmp\.' | tail -n "+$((WEB_KEEP_RELEASES + 1))" | while read -r old; do
    [[ releases/$old == "$WEB_RELEASE" || $old == "$keep_prev" ]] && continue
    rm -rf "${WEB_ROOT:?}/releases/$old" && log "web: pruned release $old"
  done || true
fi
docker image prune -f >/dev/null 2>&1 || log "WARN: docker image prune failed"
