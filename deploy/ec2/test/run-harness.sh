#!/usr/bin/env bash
# Harness for deploy/ec2/deploy.sh: runs the real script against fake docker/nginx/curl/aws (fakebin/ first in
# PATH) and checks the blue/green state after each scenario. No root, docker daemon, nginx or AWS needed.
# Usage: deploy/ec2/test/run-harness.sh [workdir]   (default: a new mktemp directory)
# What this does NOT prove: real docker/nginx/SSM behaviour, ECR auth, nginx config syntax, timing, the SSM
# document as interpreted by the real SSM Agent (section "SSM document" only renders it with python3 and runs it with sh).
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
chmod 600 "$BACKEND_ENV_FILE"
cp "$HERE/../nginx/upstream-backend.conf" "$NGINX_UPSTREAM_FILE"   # bootstrap state: points at blue, nothing running

PASS=0; FAIL=0; RC=0; OUT=""
check() { if eval "$2"; then PASS=$((PASS + 1)); echo "  ok   - $1"; else FAIL=$((FAIL + 1)); echo "  FAIL - $1"; fi; }
run() {
  local title=$1; shift
  echo "== $title"
  : >"$FAKE_STATE/reloads"; rm -f "$FAKE_STATE/term-sent"
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
check "direct smoke of the new color before the switch (client-config + configured)" 'grep -q "smoke ok (direct blue): http://127.0.0.1:18081 maintenance=false" <<<"$OUT" && grep -q "smoke ok (via nginx)" <<<"$OUT"'
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

run "12. SSM lookup fails (AccessDenied) -> die before any container change (B3)" env FAKE_SSM_FAIL=1 "$DEPLOY" "$B3"
check "exit 1 with SSM error" '[[ $RC -eq 1 ]] && grep -q "SSM lookup of /ddareungi/production/backend/ failed" <<<"$OUT"'
check "blue B1 serving, stopped green B2 untouched, no reload" 'running $BLUE && [[ $(live_port) == 18081 && $(reloads) -eq 0 ]] && ! running $GREEN && [[ $(image_of $GREEN) == "$B2" ]]'
check "no secrets file left behind" '[[ -z $(ls -A $SECRETS_DIR) ]]'

run "13. SSM path returns no parameters -> die (B3)" env FAKE_SSM_EMPTY=1 "$DEPLOY" "$B3"
check "exit 1, 'returned no parameters'" '[[ $RC -eq 1 ]] && grep -q "returned no parameters" <<<"$OUT"'
check "blue B1 still serving" 'running $BLUE && [[ $(live_port) == 18081 && $(cat $STATE_DIR/backend.current) == "$B1" ]]'

run "14. new color answers configured:false -> direct smoke fails before nginx changes (B3)" env FAKE_CONFIGURED_FALSE_COLOR=green "$DEPLOY" "$B3"
check "exit 1, reason logged" '[[ $RC -eq 1 ]] && grep -q "\"configured\":true" <<<"$OUT" && grep -q "direct smoke of ddareungi-backend-green failed" <<<"$OUT"'
check "nginx untouched (0 reloads), new green removed, blue B1 serving" '[[ $(reloads) -eq 0 && $(file_port) == 18081 && $(live_port) == 18081 ]] && ! exists $GREEN && running $BLUE'
check "state still B1" '[[ $(cat $STATE_DIR/backend.current) == "$B1" ]]'
check "rollback container log masked (SSM value, password=, Authorization)" 'grep -q "fake logs of" <<<"$OUT" && ! grep -q -e fake-db-pass -e hunter2 -e eyJhbGciOi <<<"$OUT" && grep -q "password=\*\*\*" <<<"$OUT" && grep -q "Authorization: \*\*\*" <<<"$OUT"'

run "15. configured:false seen only through nginx -> proxy smoke fails, upstream restored (B3)" env FAKE_PROXY_CONFIGURED_FALSE_COLOR=green "$DEPLOY" "$B3"
check "exit 1, direct smoke passed, proxy smoke failed on configured" '[[ $RC -eq 1 ]] && grep -q "smoke ok (direct green)" <<<"$OUT" && grep -q "smoke (via nginx).*configured" <<<"$OUT"'
check "upstream back to blue (2 reloads), new green removed, state B1" '[[ $(file_port) == 18081 && $(live_port) == 18081 && $(reloads) -eq 2 ]] && ! exists $GREEN && running $BLUE && [[ $(cat $STATE_DIR/backend.current) == "$B1" ]]'

run "16. rollback log disabled (ROLLBACK_LOG_LINES=0, readiness failure on green)" env ROLLBACK_LOG_LINES=0 FAKE_READY_FAIL_COLOR=green "$DEPLOY" "$B3"
check "exit 1, no container log printed" '[[ $RC -eq 1 ]] && ! grep -q "fake logs of" <<<"$OUT" && grep -q "ROLLBACK_LOG_LINES=0" <<<"$OUT"'

run "17. SIGTERM right after the nginx switch (SSM cancel) -> trap rolls back (B3)" env FAKE_TERM_AFTER_RELOAD=1 "$DEPLOY" "$B3"
check "exit 143, signal logged" '[[ $RC -eq 143 ]] && grep -q "received SIGTERM" <<<"$OUT"'
check "upstream restored to blue and reloaded (switch + restore)" '[[ $(file_port) == 18081 && $(live_port) == 18081 && $(reloads) -eq 2 ]]'
check "new green removed, blue B1 running, state B1" '! exists $GREEN && running $BLUE && [[ $(cat $STATE_DIR/backend.current) == "$B1" ]]'

mkdir -p "$STATE_DIR/backend.current.new"   # a directory: the state write fails even as root
run "18. state file cannot be written before commit -> rollback (B3)" "$DEPLOY" "$B3"
check "exit 1, reason logged" '[[ $RC -eq 1 ]] && grep -q "cannot write $STATE_DIR/backend.current.new" <<<"$OUT"'
check "upstream restored to blue, new green removed, state B1" '[[ $(live_port) == 18081 ]] && ! exists $GREEN && running $BLUE && [[ $(cat $STATE_DIR/backend.current) == "$B1" ]]'
rmdir "$STATE_DIR/backend.current.new"

run "19. --force-restart of the active digest B1 -> recreated on green" "$DEPLOY" --force-restart "$B1"
check "exit 0" '[[ $RC -eq 0 ]]'
check "green runs B1, blue stopped, nginx -> 18082" 'running $GREEN && [[ $(image_of $GREEN) == "$B1" ]] && ! running $BLUE && [[ $(live_port) == 18082 ]]'
check "current B1, previous not rotated (B2), history says restart" '[[ $(cat $STATE_DIR/backend.current) == "$B1" && $(cat $STATE_DIR/backend.previous) == "$B2" ]] && tail -1 $STATE_DIR/history.log | grep -q " backend restart green "'
check "no pull needed for the running image" '! grep -q "pulled" <<<"$OUT"'

run "20. maintenance on -> env file updated, active image restarted on blue" "$DEPLOY" maintenance on
check "exit 0" '[[ $RC -eq 0 ]]'
check "backend.env has CLIENT_WEB_MAINTENANCE=true (mode kept)" 'grep -qx "CLIENT_WEB_MAINTENANCE=true" $BACKEND_ENV_FILE && [[ $(stat -c %a $BACKEND_ENV_FILE) == 600 ]]'
check "blue runs B1 with maintenance=true, nginx -> 18081, green stopped" 'running $BLUE && [[ $(image_of $BLUE) == "$B1" ]] && grep -qx "CLIENT_WEB_MAINTENANCE=true" $FAKE_STATE/containers/$BLUE/env && [[ $(live_port) == 18081 ]] && ! running $GREEN'
check "smoke checked client-config maintenance=true (routes/status skipped)" 'grep -q "smoke ok (via nginx): http://127.0.0.1:18080 maintenance=true" <<<"$OUT"'
check "history maintenance-on" 'tail -1 $STATE_DIR/history.log | grep -q "maintenance-on"'

run "21. maintenance off fails (green never ready) -> env file restored" env FAKE_READY_FAIL_COLOR=green "$DEPLOY" maintenance off
check "exit 1" '[[ $RC -eq 1 ]]'
check "backend.env back to CLIENT_WEB_MAINTENANCE=true, blue still serving" 'grep -qx "CLIENT_WEB_MAINTENANCE=true" $BACKEND_ENV_FILE && running $BLUE && [[ $(live_port) == 18081 ]] && ! exists $GREEN'

run "22. maintenance off blocked by an SSM parameter of the same name" env FAKE_SSM_EXTRA=$'/ddareungi/production/backend/CLIENT_WEB_MAINTENANCE\ttrue' "$DEPLOY" maintenance off
check "exit 1, override explained, env restored, nothing started" '[[ $RC -eq 1 ]] && grep -q "SSM parameter overrides" <<<"$OUT" && grep -qx "CLIENT_WEB_MAINTENANCE=true" $BACKEND_ENV_FILE && ! exists $GREEN && [[ $(reloads) -eq 0 ]]'

run "23. maintenance off -> green with maintenance=false, routes/status checked again" "$DEPLOY" maintenance off
check "exit 0, env false, green B1 live, configured smoke ok" '[[ $RC -eq 0 ]] && grep -qx "CLIENT_WEB_MAINTENANCE=false" $BACKEND_ENV_FILE && running $GREEN && [[ $(image_of $GREEN) == "$B1" && $(live_port) == 18082 ]] && grep -q "smoke ok (via nginx): http://127.0.0.1:18080 maintenance=false" <<<"$OUT"'
check "only one CLIENT_WEB_MAINTENANCE line" '[[ $(grep -c "^CLIENT_WEB_MAINTENANCE=" $BACKEND_ENV_FILE) -eq 1 ]]'

run "24. usage: maintenance needs on|off" "$DEPLOY" maintenance maybe
check "exit 2" '[[ $RC -eq 2 ]]'

run "25. normal deploy after maintenance (B2 on blue)" "$DEPLOY" "$B2"
check "exit 0, blue B2 live, previous B1" '[[ $RC -eq 0 ]] && running $BLUE && [[ $(image_of $BLUE) == "$B2" && $(live_port) == 18081 && $(cat $STATE_DIR/backend.previous) == "$B1" ]]'

# ---------- SSM document (deploy/aws/ssm-document-deploy.json) ----------
echo "== 26. SSM document: parameter patterns and rendered command"
DOC="$HERE/../../aws/ssm-document-deploy.json"
STUB="$WORK/stub-deploy.sh"
printf '#!/bin/sh\necho "ARGS:$*"\n' >"$STUB"; chmod +x "$STUB"
STUB_SUM=$(sha256sum "$STUB" | cut -d' ' -f1)
render() {  # render <action> <backendRef> <webRef> <sha> -> runs the rendered runCommand with sh, prints output
  python3 -I - "$DOC" "$STUB" "$@" <<'PY'
import json, re, subprocess, sys
doc_path, stub, action, backend, web, sha = sys.argv[1:7]
doc = json.load(open(doc_path))
values = {"action": action, "backendRef": backend, "webRef": web, "scriptSha256": sha, "executionTimeout": "1200"}
for name, spec in doc["parameters"].items():
    v = values[name]
    if "allowedValues" in spec and v not in spec["allowedValues"]:
        print("REJECTED:" + name); sys.exit(0)
    if "allowedPattern" in spec and not re.fullmatch(spec["allowedPattern"], v):
        print("REJECTED:" + name); sys.exit(0)
lines = doc["mainSteps"][0]["inputs"]["runCommand"]
script = "\n".join(lines)
for name, v in values.items():
    script = re.sub(r"\{\{\s*" + name + r"\s*\}\}", v, script)
assert "{{" not in script
script = script.replace("/opt/ddareungi/bin/deploy.sh", stub)
r = subprocess.run(["sh", "-c", script], capture_output=True, text=True)
print("RC:%d" % r.returncode); print(r.stdout.strip()); print(r.stderr.strip())
PY
}
R=$(render deploy "$B1" none "$STUB_SUM")
check "deploy, backend only -> deploy.sh <backend>" 'grep -qx "ARGS:$B1" <<<"$R" && grep -qx "RC:0" <<<"$R"'
R=$(render deploy "$B1" "$W1" "$STUB_SUM")
check "deploy with web -> deploy.sh <backend> <web>" 'grep -qx "ARGS:$B1 $W1" <<<"$R"'
R=$(render force-restart "$B1" none "$STUB_SUM")
check "force-restart -> deploy.sh --force-restart <backend>" 'grep -qx "ARGS:--force-restart $B1" <<<"$R"'
R=$(render maintenance-on none none "$STUB_SUM")
check "maintenance-on -> deploy.sh maintenance on" 'grep -qx "ARGS:maintenance on" <<<"$R"'
R=$(render maintenance-off none none "$STUB_SUM")
check "maintenance-off -> deploy.sh maintenance off" 'grep -qx "ARGS:maintenance off" <<<"$R"'
R=$(render deploy none none "$STUB_SUM")
check "deploy without backendRef -> exit 2" 'grep -qx "RC:2" <<<"$R" && ! grep -q "^ARGS:" <<<"$R"'
R=$(render deploy "$B1" none "$(printf "%064d" 0)")
check "host script sha256 mismatch -> exit 3, script not run" 'grep -qx "RC:3" <<<"$R" && ! grep -q "^ARGS:" <<<"$R"'
R=$(render deploy "$B1'; touch /tmp/pwned; '" none "$STUB_SUM")
check "quote injection in backendRef rejected by allowedPattern" 'grep -qx "REJECTED:backendRef" <<<"$R"'
R=$(render deploy "$REG/backend:latest" none "$STUB_SUM")
check "tag (not digest) rejected by allowedPattern" 'grep -qx "REJECTED:backendRef" <<<"$R"'
R=$(render 'deploy;id' "$B1" none "$STUB_SUM")
check "unknown action rejected by allowedValues" 'grep -qx "REJECTED:action" <<<"$R"'

echo
echo "harness: $PASS passed, $FAIL failed (workdir $WORK)"
[[ $FAIL -eq 0 ]]
