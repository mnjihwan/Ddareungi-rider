#!/usr/bin/env bash
# Blue/green deploy of the ddareungrider backend (and optionally the static web bundle) on one EC2 host.
#
# Usage: deploy.sh <backend-image@sha256:...> [<web-image@sha256:...>]
#
# Invoked by .github/workflows/deploy.yml through SSM Run Command (AWS-RunShellScript, runs as root).
# Images are always referenced by digest (tags are immutable and only used to find the digest).
#
# Flow (any failure before "commit" restores the previous state and exits non-zero):
#   1. ECR login, pull by digest (backend; web bundle extracted to a new release directory)
#   2. active color = the color the nginx upstream file points at, if that container is running
#   3. new container on the other color's localhost port (management port 8090 not published)
#   4. readiness: docker exec curl http://localhost:8090/actuator/health/readiness until timeout
#      -> failure: remove the new container, keep the old one serving, exit 1
#   5. atomically replace the upstream file (mv), nginx -t && nginx -s reload
#   6. smoke through nginx (GET /api/v1/routes/status == 200)
#      -> failure: restore the previous upstream file, reload, remove the new container, exit 1
#   7. web: switch the `current` symlink atomically, smoke GET / == 200 (failure: revert web and backend)
#   8. commit: record current/previous digests, drain then stop the old container (kept, stopped)
#
# Idempotent: re-running with the digest that is already active changes nothing.
# Configuration: environment variables, optionally from $DEPLOY_CONFIG (default /etc/ddareungi/deploy.env).
set -euo pipefail
umask 022

log() { printf '%s [deploy] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
usage() {
  echo "usage: $0 <registry/repo@sha256:<64 hex>> [<registry/repo@sha256:<64 hex>>]" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage
BACKEND_REF=$1
WEB_REF=${2:-}

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
SMOKE_URL=${SMOKE_URL:-http://127.0.0.1:18080/api/v1/routes/status}
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

REF_PATTERN='^[a-z0-9.-]+(:[0-9]+)?/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$'
[[ $BACKEND_REF =~ $REF_PATTERN ]] || { log "backend image must be <registry>/<repo>@sha256:<digest>: $BACKEND_REF"; usage; }
if [[ -n $WEB_REF && ! $WEB_REF =~ $REF_PATTERN ]]; then
  log "web image must be <registry>/<repo>@sha256:<digest>: $WEB_REF"; usage
fi
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
SECRETS_FILE=""
COMMITTED=0

nginx_reload() { "$NGINX_BIN" -t && "$NGINX_BIN" -s reload; }

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
  if [[ -n $NEW_CONTAINER ]]; then
    log "last logs of $NEW_CONTAINER:"
    docker logs --tail 80 "$NEW_CONTAINER" >&2 2>&1
    docker rm -f "$NEW_CONTAINER" >/dev/null 2>&1 && log "removed new container $NEW_CONTAINER"
  fi
}

on_exit() {
  local rc=$?
  trap - EXIT
  if [[ $rc -ne 0 && $COMMITTED -eq 0 ]]; then rollback; fi
  [[ -n $SECRETS_FILE ]] && rm -f "$SECRETS_FILE"
  if [[ $rc -eq 0 ]]; then log "done"; else log "FAILED (exit $rc); previous version keeps serving"; fi
  exit "$rc"
}
trap on_exit EXIT

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

ecr_login() {
  local registry=${1%%/*} region=${AWS_REGION:-}
  if [[ -z $region && $registry =~ \.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com$ ]]; then region=${BASH_REMATCH[1]}; fi
  [[ -n $region ]] || die "AWS_REGION is not set and cannot be derived from $registry"
  aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "$registry" >/dev/null
  log "ecr login ok: $registry"
}

load_secrets() {
  # SSM Parameter Store SecureString -> env file readable by root only. Parameter name basename = env name.
  # Values must be single-line (the text output is tab/line based).
  [[ -n $SSM_PARAMETER_PATH ]] || return 0
  local region=${AWS_REGION:-}
  [[ -n $region ]] || region=$(sed -nE 's#^[^/]*\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com/.*#\1#p' <<<"$BACKEND_REF")
  mkdir -p "$SECRETS_DIR"; chmod 700 "$SECRETS_DIR"
  SECRETS_FILE="$SECRETS_DIR/backend.$$.env"
  (umask 077; : >"$SECRETS_FILE")
  local name value count=0
  while IFS=$'\t' read -r name value; do
    [[ -n $name ]] || continue
    name=${name##*/}
    [[ $name =~ ^[A-Z_][A-Z0-9_]*$ ]] || die "SSM parameter name is not a valid env name: $name"
    printf '%s=%s\n' "$name" "$value" >>"$SECRETS_FILE"
    count=$((count + 1))
  done < <(aws ssm get-parameters-by-path --region "$region" --path "$SSM_PARAMETER_PATH" --recursive \
    --with-decryption --query 'Parameters[].[Name,Value]' --output text)
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

BACKEND_CHANGE=1
if [[ -n $ACTIVE && $ACTIVE_REF == "$BACKEND_REF" ]]; then
  BACKEND_CHANGE=0
  log "backend $BACKEND_REF is already active on $ACTIVE; backend step skipped"
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

# ---------- 2. pull ----------
ecr_login "$BACKEND_REF"
if [[ $WEB_CHANGE -eq 1 && ${WEB_REF%%/*} != "${BACKEND_REF%%/*}" ]]; then ecr_login "$WEB_REF"; fi
if [[ $BACKEND_CHANGE -eq 1 ]]; then docker pull -q "$BACKEND_REF" >/dev/null; log "pulled $BACKEND_REF"; fi
if [[ $WEB_CHANGE -eq 1 ]]; then docker pull -q "$WEB_REF" >/dev/null; log "pulled $WEB_REF"; prepare_web; fi

# ---------- 3. start the new color and wait for readiness ----------
if [[ $BACKEND_CHANGE -eq 1 ]]; then
  TARGET=$(other_color "${ACTIVE:-green}")   # first deploy -> blue
  TARGET_NAME=$(container_name "$TARGET")
  TARGET_PORT=$(port_of "$TARGET")
  docker network inspect "$DOCKER_NETWORK" >/dev/null 2>&1 || docker network create "$DOCKER_NETWORK" >/dev/null
  load_secrets
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

  # ---------- 4. switch nginx ----------
  if [[ -f $NGINX_UPSTREAM_FILE ]]; then
    UPSTREAM_BACKUP="$STATE_DIR/upstream-backend.conf.prev"
    cp -f "$NGINX_UPSTREAM_FILE" "$UPSTREAM_BACKUP"
  fi
  write_upstream "$TARGET_PORT"
  UPSTREAM_SWITCHED=1
  nginx_reload || die "nginx -t / reload failed with the new upstream"
  log "nginx upstream -> $TARGET (127.0.0.1:$TARGET_PORT)"

  # ---------- 5. smoke through the proxy ----------
  smoke "$SMOKE_URL" || die "smoke through nginx failed after switching to $TARGET"
fi

# ---------- 6. web switch ----------
if [[ $WEB_CHANGE -eq 1 ]]; then
  WEB_PREV_TARGET=$(readlink "$WEB_ROOT/current" 2>/dev/null || true)
  ln -sfn "$WEB_RELEASE" "$WEB_ROOT/current.tmp.$$"
  mv -Tf "$WEB_ROOT/current.tmp.$$" "$WEB_ROOT/current"
  WEB_SWITCHED=1
  log "web: current -> $WEB_RELEASE"
  smoke "$WEB_SMOKE_URL" || die "web smoke failed"
fi

# ---------- 7. commit ----------
COMMITTED=1
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
if [[ $BACKEND_CHANGE -eq 1 ]]; then
  [[ -f $STATE_DIR/backend.current ]] && cp -f "$STATE_DIR/backend.current" "$STATE_DIR/backend.previous"
  echo "$BACKEND_REF" >"$STATE_DIR/backend.current"
  echo "$now backend $TARGET $BACKEND_REF (was ${ACTIVE_REF:-none})" >>"$STATE_DIR/history.log"
fi
if [[ $WEB_CHANGE -eq 1 ]]; then
  [[ -f $STATE_DIR/web.current ]] && cp -f "$STATE_DIR/web.current" "$STATE_DIR/web.previous"
  echo "$WEB_REF" >"$STATE_DIR/web.current"
  echo "$now web $WEB_RELEASE $WEB_REF (was ${WEB_PREV_TARGET:-none})" >>"$STATE_DIR/history.log"
fi
log "committed"

# ---------- 8. drain and stop the old color (failures here only warn) ----------
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
