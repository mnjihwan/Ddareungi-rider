#!/usr/bin/env bash
# Harness for deploy/ec2/deploy.sh: runs the real script against fake docker/nginx/curl/aws (fakebin/ first in
# PATH) and checks the blue/green state after each scenario. No root, docker daemon, nginx or AWS needed.
# Usage: deploy/ec2/test/run-harness.sh [workdir]   (default: a new mktemp directory)
# What this does NOT prove: real docker/nginx/SSM behaviour, ECR auth, nginx config syntax, timing.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DEPLOY="$HERE/../deploy.sh"
WORK=${1:-$(mktemp -d)}
mkdir -p "$WORK"
export FAKE_STATE="$WORK/state"
export PATH="$HERE/fakebin:$PATH"
export DEPLOY_CONFIG="$WORK/none.env"
export BACKEND_ENV_FILE="$WORK/backend.env"
export SSM_PARAMETER_PATH=/ddareungi/production/backend/
export AWS_REGION=ap-northeast-2
export NGINX_UPSTREAM_FILE="$WORK/nginx/upstream-backend.conf"
export STATE_DIR="$WORK/deploy-state"
export WEB_ROOT="$WORK/web"
export SECRETS_DIR="$WORK/secrets"
export LOCK_FILE="$WORK/lock/deploy.lock"
export READINESS_TIMEOUT=2 READINESS_INTERVAL=1 SMOKE_ATTEMPTS=2 SMOKE_INTERVAL=0 DRAIN_SECONDS=0 STOP_TIMEOUT=1

REG="000000000000.dkr.ecr.ap-northeast-2.amazonaws.com"   # fake placeholder registry
d() { printf 'sha256:%s%0*d' "$1" $((64 - ${#1})) 0; }   # digest with a distinguishing hex prefix
B1="$REG/backend@$(d b001)"; B2="$REG/backend@$(d b002)"; B3="$REG/backend@$(d b003)"
W1="$REG/web@$(d a011)";     W2="$REG/web@$(d a012)"
rel() { printf 'releases/%s%0*d' "$1" $((16 - ${#1})) 0; }

rm -rf "$FAKE_STATE" "$WORK/nginx" "$STATE_DIR" "$WEB_ROOT" "$SECRETS_DIR" "$WORK/lock"
mkdir -p "$FAKE_STATE" "$WORK/nginx"
printf 'SPRING_PROFILES_ACTIVE=infra\nSERVER_FORWARD_HEADERS_STRATEGY=native\n' >"$BACKEND_ENV_FILE"
cp "$HERE/../nginx/upstream-backend.conf" "$NGINX_UPSTREAM_FILE"   # bootstrap state: points at blue, nothing running

PASS=0; FAIL=0; RC=0; OUT=""
check() { if eval "$2"; then PASS=$((PASS + 1)); echo "  ok   - $1"; else FAIL=$((FAIL + 1)); echo "  FAIL - $1"; fi; }
run() {
  local title=$1; shift
  echo "== $title"
  : >"$FAKE_STATE/reloads"
  OUT=$("$@" 2>&1); RC=$?
  mkdir -p "$WORK/logs"
  echo "$OUT" >"$WORK/logs/${title%%.*}.log"
  echo "  exit=$RC (log: $WORK/logs/${title%%.*}.log)"
  [[ -n ${VERBOSE:-} ]] && echo "$OUT" | sed 's/^/     | /'
}
live_port() { grep -Eo '127\.0\.0\.1:[0-9]+' "$FAKE_STATE/live-upstream" 2>/dev/null | cut -d: -f2; }
file_port() { grep -Eo '127\.0\.0\.1:[0-9]+' "$NGINX_UPSTREAM_FILE" | cut -d: -f2; }
running() { [[ -f $FAKE_STATE/containers/$1/running && $(cat "$FAKE_STATE/containers/$1/running") == true ]]; }
exists() { [[ -d $FAKE_STATE/containers/$1 ]]; }
image_of() { cat "$FAKE_STATE/containers/$1/image" 2>/dev/null; }
reloads() { wc -l <"$FAKE_STATE/reloads" | tr -d ' '; }
BLUE=ddareungi-backend-blue; GREEN=ddareungi-backend-green

run "1. usage: tag instead of digest is rejected" "$DEPLOY" "$REG/backend:latest"
check "exit code 2" '[[ $RC -eq 2 ]]'
check "nothing started" '! exists $BLUE && ! exists $GREEN'

run "2. first deploy (backend B1 + web W1) -> blue" "$DEPLOY" "$B1" "$W1"
check "exit 0" '[[ $RC -eq 0 ]]'
check "blue running B1" 'running $BLUE && [[ $(image_of $BLUE) == "$B1" ]]'
check "upstream file and live nginx -> 18081" '[[ $(file_port) == 18081 && $(live_port) == 18081 ]]'
check "management port not published, only 127.0.0.1:18081:8080" 'grep -qx "127.0.0.1:18081:8080" $FAKE_STATE/containers/$BLUE/args && ! grep -q 8090: $FAKE_STATE/containers/$BLUE/args'
check "forward headers + management port forced" 'grep -qx "SERVER_FORWARD_HEADERS_STRATEGY=native" $FAKE_STATE/containers/$BLUE/args && grep -qx "MANAGEMENT_SERVER_PORT=8090" $FAKE_STATE/containers/$BLUE/args'
check "SSM secrets passed as env file and removed afterwards" 'grep -q "^MYSQL_PASSWORD=fake-db-pass$" $FAKE_STATE/envfiles/backend.*.env && [[ -z $(ls -A $SECRETS_DIR) ]]'
check "secret values not printed" '! grep -q fake-db-pass <<<"$OUT"'
check "web current -> W1 release" '[[ $(readlink $WEB_ROOT/current) == $(rel a011) ]] && grep -q "$W1" $WEB_ROOT/current/index.html'
check "state current=B1" '[[ $(cat $STATE_DIR/backend.current) == "$B1" ]]'

run "3. second deploy (B2 + W2) -> green, blue drained and stopped" "$DEPLOY" "$B2" "$W2"
check "exit 0" '[[ $RC -eq 0 ]]'
check "green running B2" 'running $GREEN && [[ $(image_of $GREEN) == "$B2" ]]'
check "blue stopped (kept)" 'exists $BLUE && ! running $BLUE'
check "nginx -> 18082" '[[ $(file_port) == 18082 && $(live_port) == 18082 ]]'
check "state current=B2 previous=B1" '[[ $(cat $STATE_DIR/backend.current) == "$B2" && $(cat $STATE_DIR/backend.previous) == "$B1" ]]'
check "web switched to W2" 'grep -q "$W2" $WEB_ROOT/current/index.html'

run "4. idempotent re-run of B2 + W2" "$DEPLOY" "$B2" "$W2"
check "exit 0" '[[ $RC -eq 0 ]]'
check "reports nothing to do" 'grep -q "nothing to do" <<<"$OUT"'
check "no reload, green still active" '[[ $(reloads) -eq 0 ]] && running $GREEN && [[ $(live_port) == 18082 ]]'

run "5. readiness failure (B3, blue never ready)" env FAKE_READY_FAIL_COLOR=blue "$DEPLOY" "$B3"
check "exit 1" '[[ $RC -eq 1 ]]'
check "new blue container removed" '! exists $BLUE'
check "green B2 still serving, nginx untouched" 'running $GREEN && [[ $(file_port) == 18082 && $(live_port) == 18082 && $(reloads) -eq 0 ]]'
check "state still B2" '[[ $(cat $STATE_DIR/backend.current) == "$B2" ]]'
check "reason logged" 'grep -q "not ready within" <<<"$OUT"'

run "6. container exits during startup (B3)" env FAKE_EXIT_ON_START_COLOR=blue "$DEPLOY" "$B3"
check "exit 1, early" '[[ $RC -eq 1 ]] && grep -q "exited before becoming ready" <<<"$OUT"'
check "green still serving" 'running $GREEN && [[ $(live_port) == 18082 ]] && ! exists $BLUE'

run "7. smoke failure after switch (B3 on blue answers 503 via proxy)" env FAKE_SMOKE_FAIL_COLOR=blue "$DEPLOY" "$B3"
check "exit 1" '[[ $RC -eq 1 ]]'
check "upstream restored to green and reloaded twice (switch + restore)" '[[ $(file_port) == 18082 && $(live_port) == 18082 && $(reloads) -eq 2 ]]'
check "new blue removed, green running" '! exists $BLUE && running $GREEN'
check "state still B2" '[[ $(cat $STATE_DIR/backend.current) == "$B2" ]]'

run "8. nginx -t failure after switch (B3)" env FAKE_NGINX_T_FAIL=1 "$DEPLOY" "$B3"
check "exit 1" '[[ $RC -eq 1 ]]'
check "upstream file restored to green, new blue removed" '[[ $(file_port) == 18082 ]] && ! exists $BLUE && running $GREEN'

run "9. web smoke failure reverts web and backend (B3 + W1)" env FAKE_WEB_SMOKE_FAIL=1 "$DEPLOY" "$B3" "$W1"
check "exit 1" '[[ $RC -eq 1 ]]'
check "web current back to W2 release" '[[ $(readlink $WEB_ROOT/current) == $(rel a012) ]]'
check "backend back to green B2" '[[ $(file_port) == 18082 && $(live_port) == 18082 ]] && running $GREEN && ! exists $BLUE'

run "10. rollback deploy of the previous image B1 (workflow_dispatch image_tag)" "$DEPLOY" "$B1"
check "exit 0" '[[ $RC -eq 0 ]]'
check "blue running B1, green stopped" 'running $BLUE && [[ $(image_of $BLUE) == "$B1" ]] && ! running $GREEN'
check "nginx -> 18081" '[[ $(live_port) == 18081 ]]'
check "state current=B1 previous=B2" '[[ $(cat $STATE_DIR/backend.current) == "$B1" && $(cat $STATE_DIR/backend.previous) == "$B2" ]]'

run "11. concurrent run is refused by the lock" bash -c 'exec 8>"$LOCK_FILE"; flock -n 8; "$0" "$1"' "$DEPLOY" "$B2"
check "exit 1 with lock message" '[[ $RC -eq 1 ]] && grep -q "another deploy is running" <<<"$OUT"'
check "blue B1 untouched" 'running $BLUE && [[ $(live_port) == 18081 ]]'

echo
echo "harness: $PASS passed, $FAIL failed (workdir $WORK)"
[[ $FAIL -eq 0 ]]
