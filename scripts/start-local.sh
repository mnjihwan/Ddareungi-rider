#!/usr/bin/env bash
# Local launcher for Linux/macOS (Windows: scripts/start-local.ps1).
#
#   scripts/start-local.sh sample   # default. No Docker/keys: in-memory sample stations (3 stations near Gangnam)
#   scripts/start-local.sh infra    # Docker MySQL+Redis, collector+backend run on the host JVM
#   scripts/start-local.sh real     # everything (MySQL, Redis, collector, backend) in Docker Compose
#
# Web UI: http://localhost:5173  /  API: http://localhost:8080/api/v1
# Actuator (internal management port): http://localhost:8090/actuator/health/readiness (MANAGEMENT_SERVER_PORT)
set -euo pipefail

MODE="${1:-sample}"
case "$MODE" in sample|infra|real) ;; *) echo "Usage: $0 [sample|infra|real]" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RIDER="$ROOT/Ddareungi-rider"
WEB="$RIDER/web"
COLLECTOR="$ROOT/bikeseoul_data"
STATE="$ROOT/.local"
ENV_FILE="$ROOT/.env"
mkdir -p "$STATE"

log() { printf '[start-local] %s\n' "$*"; }
fail() { printf '[start-local] ERROR: %s\n' "$*" >&2; exit 1; }

# Load only known variables from .env. Values are never evaluated as shell code or printed.
ALLOWED_VARS=" SEOUL_API_KEY SEOUL_API_BASE_URL KAKAO_MAPS_REST_API_KEY KAKAO_MAPS_JAVASCRIPT_KEY ORS_API_KEY 
 MYSQL_DATABASE MYSQL_USER MYSQL_PASSWORD MYSQL_ROOT_PASSWORD MYSQL_HOST MYSQL_PORT EXTERNAL_MYSQL 
 EXTERNAL_REDIS REDIS_HOST REDIS_PORT REALTIME_REDIS_READ_MODE VITE_FORECAST_API_PATH MANAGEMENT_SERVER_PORT 
 COLLECTOR_CRON EXTENDED_COLLECTION_ENABLED OD_DOWNLOAD_ENABLED "
load_env() {
    [[ -f "$ENV_FILE" ]] || return 0
    local line name value
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=(.*)$ ]] || continue
        name="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        [[ "$ALLOWED_VARS" == *" $name "* ]] || continue
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
        export "$name=$value"
    done < "$ENV_FILE"
}

port_in_use() {
    if command -v ss >/dev/null 2>&1; then ss -ltn "sport = :$1" | grep -q LISTEN
    elif command -v lsof >/dev/null 2>&1; then lsof -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
    else (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
    fi
}

wait_url() {
    local url="$1" seconds="${2:-120}" deadline=$((SECONDS + ${2:-120}))
    while (( SECONDS < deadline )); do
        curl -fsS -o /dev/null --max-time 3 "$url" 2>/dev/null && return 0
        sleep 2
    done
    fail "Startup timed out after ${seconds}s: $url (see $STATE/*.log)"
}

start_bg() {
    # start_bg <name> <workdir> <command...>; PID is saved for stop-local.sh
    local name="$1" dir="$2"; shift 2
    # Background only the command itself; stdin/stdout are detached so callers' pipes can close.
    (
        cd "$dir" || exit 1
        nohup "$@" > "$STATE/$name.log" 2>&1 < /dev/null &
        echo $! > "$STATE/$name.pid"
    )
    log "$name started (pid $(cat "$STATE/$name.pid"), log: .local/$name.log)"
}

require_env() {
    local name
    for name in "$@"; do [[ -n "${!name:-}" ]] || fail "Set $name in $ENV_FILE (copy .env.example first)."; done
}

require_docker() {
    command -v docker >/dev/null || fail "Docker is required. Install Docker Desktop, or set EXTERNAL_MYSQL=true and EXTERNAL_REDIS=true in .env."
    docker info >/dev/null 2>&1 || fail "Docker engine is not running. Start Docker Desktop and wait until it shows 'Engine running', then retry."
}

tcp_open() {
    (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null
}

latest_jar() {
    find "$1" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2-
}

# Prefer JAVA_HOME so Gradle's toolchain lookup and the launched JVMs use the same JDK.
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi

check_java() {
    command -v java >/dev/null || fail "Java 21 is required. Install JDK 21 (e.g. Eclipse Temurin 21) and set JAVA_HOME."
    local version major
    version="$(java -version 2>&1 | grep -m1 -Eo 'version "[^"]+"' | cut -d'"' -f2)"
    major="${version%%.*}"
    [[ "$major" == "1" ]] && major="$(cut -d. -f2 <<< "$version")"
    [[ "$major" == "21" ]] || fail "JDK 21 is required (found: ${version:-unknown} at $(command -v java)). Install JDK 21 and set JAVA_HOME to it, e.g. export JAVA_HOME=\"/c/Program Files/Eclipse Adoptium/jdk-21...\""
}

check_node() {
    command -v node >/dev/null && command -v npm >/dev/null || fail "Node.js 22 LTS (or 20.19+) and npm are required."
    local version major minor
    version="$(node -v)"; version="${version#v}"
    major="${version%%.*}"; minor="$(cut -d. -f2 <<< "$version")"
    # Vite 8 / rolldown require ^20.19.0 || >=22.12.0.
    if (( major > 22 )) || (( major == 22 && minor >= 12 )) || (( major == 20 && minor >= 19 )); then return 0; fi
    fail "Node.js $version is too old for Vite 8 (needs 20.19+ or 22.12+). Install Node.js 22 LTS."
}

load_env
MANAGEMENT_PORT="${MANAGEMENT_SERVER_PORT:-8090}"
[[ "$MANAGEMENT_PORT" =~ ^[1-9][0-9]{0,4}$ ]] && (( MANAGEMENT_PORT <= 65535 )) || fail "MANAGEMENT_SERVER_PORT must be a port number (1-65535)."
# Not exported globally: the collector (also Spring Boot) would inherit it and move its own actuator port.
# It is passed only to the backend and web processes below.
unset MANAGEMENT_SERVER_PORT
check_java
check_node
command -v curl >/dev/null || fail "curl is required."
port_in_use 5173 && fail "Port 5173 is already in use. Run scripts/stop-local.sh first."
[[ "$MODE" != "real" ]] && port_in_use 8080 && fail "Port 8080 is already in use. Run scripts/stop-local.sh first."
[[ "$MODE" != "real" ]] && port_in_use "$MANAGEMENT_PORT" && fail "Port $MANAGEMENT_PORT (backend management) is already in use. Run scripts/stop-local.sh first or set MANAGEMENT_SERVER_PORT."

log "Installing web dependencies (if needed) and type-checking"
( cd "$WEB" && { [[ -d node_modules ]] || npm ci --no-audit --no-fund; } && npm run --silent typecheck )

if [[ "$MODE" == "sample" || "$MODE" == "infra" ]]; then
    log "Building backend jar"
    ( cd "$RIDER" && sed -i.bak 's/\r$//' gradlew && rm -f gradlew.bak && sh gradlew :backend:bootJar --no-daemon -q )
    BACKEND_JAR="$(latest_jar "$RIDER/backend/build/libs")"
    [[ -n "$BACKEND_JAR" ]] || fail "Backend jar not found."
fi

case "$MODE" in
sample)
    MANAGEMENT_SERVER_PORT="$MANAGEMENT_PORT" \
        start_bg backend "$RIDER" java -jar "$BACKEND_JAR" --spring.profiles.active=local
    ;;
infra)
    # The collector refuses to start without a Seoul Open API key (station master + realtime data).
    require_env SEOUL_API_KEY MYSQL_PASSWORD
    [[ -f "$COLLECTOR/build.gradle" ]] || fail "Collector submodule missing. Run: git submodule update --init --recursive"
    EXTERNAL_MYSQL="${EXTERNAL_MYSQL:-false}"
    EXTERNAL_REDIS="${EXTERNAL_REDIS:-false}"
    containers=()
    [[ "$EXTERNAL_MYSQL" == "true" ]] || containers+=(mysql)
    [[ "$EXTERNAL_REDIS" == "true" ]] || containers+=(redis)
    if (( ${#containers[@]} > 0 )); then
        require_docker
        [[ "$EXTERNAL_MYSQL" == "true" ]] || require_env MYSQL_ROOT_PASSWORD
        log "Starting containers: ${containers[*]}"
        # Compose validates every service's variables; services that are not started only need placeholders.
        MYSQL_PASSWORD="${MYSQL_PASSWORD}" MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-unused-external-mysql}" \
            docker compose --env-file "$ENV_FILE" -f "$ROOT/compose.yaml" up -d --wait "${containers[@]}"
    else
        log "Using external MySQL and Redis; Docker is not used"
    fi
    if [[ "$EXTERNAL_MYSQL" == "true" ]]; then MYSQL_TARGET="${MYSQL_HOST:-127.0.0.1}:${MYSQL_PORT:-3306}"
    else MYSQL_TARGET="127.0.0.1:${MYSQL_PORT:-3307}"; fi
    if [[ "$EXTERNAL_REDIS" == "true" ]]; then REDIS_TARGET_HOST="${REDIS_HOST:-127.0.0.1}"; REDIS_TARGET_PORT="${REDIS_PORT:-6379}"
    else REDIS_TARGET_HOST=127.0.0.1; REDIS_TARGET_PORT="${REDIS_PORT:-6380}"; fi
    log "MySQL: $MYSQL_TARGET / Redis: $REDIS_TARGET_HOST:$REDIS_TARGET_PORT"
    tcp_open "${MYSQL_TARGET%:*}" "${MYSQL_TARGET##*:}" || fail "Cannot reach MySQL at $MYSQL_TARGET. Start MySQL or check MYSQL_HOST/MYSQL_PORT."
    tcp_open "$REDIS_TARGET_HOST" "$REDIS_TARGET_PORT" || fail "Cannot reach Redis at $REDIS_TARGET_HOST:$REDIS_TARGET_PORT. Start Redis or check REDIS_HOST/REDIS_PORT."
    log "Building collector jar"
    ( cd "$COLLECTOR" && sed -i.bak 's/\r$//' gradlew && rm -f gradlew.bak && sh gradlew bootJar --no-daemon -q )
    COLLECTOR_JAR="$(latest_jar "$COLLECTOR/build/libs")"
    [[ -n "$COLLECTOR_JAR" ]] || fail "Collector jar not found."
    MYSQL_JDBC="jdbc:mysql://$MYSQL_TARGET/${MYSQL_DATABASE:-ddareungi}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
    port_in_use 8081 && fail "Port 8081 (collector) is already in use."
    # Collector owns the schema (Flyway). Start it first and wait until migrations finished.
    # REDIS_HOST/REDIS_PORT prepare roadmap R1/R2 (collector writes realtime Redis); the current collector does
    # not read them yet, so passing them is harmless. Same target as the backend below.
    MYSQL_URL="$MYSQL_JDBC&rewriteBatchedStatements=true" MYSQL_USER="${MYSQL_USER:-ddareungi}" \
        REDIS_HOST="$REDIS_TARGET_HOST" REDIS_PORT="$REDIS_TARGET_PORT" \
        FLOW_CSV_INPUT_DIR="$COLLECTOR/data/oa-21229" USAGE_CSV_INPUT_DIR="$COLLECTOR/data/usage" \
        start_bg collector "$COLLECTOR" java -jar "$COLLECTOR_JAR"
    wait_url "http://127.0.0.1:8081/actuator/health" 180
    MYSQL_URL="$MYSQL_JDBC" MYSQL_USER="${MYSQL_USER:-ddareungi}" REDIS_HOST="$REDIS_TARGET_HOST" REDIS_PORT="$REDIS_TARGET_PORT" \
        MANAGEMENT_SERVER_PORT="$MANAGEMENT_PORT" start_bg backend "$RIDER" java -jar "$BACKEND_JAR" --spring.profiles.active=infra
    ;;
real)
    require_docker
    require_env SEOUL_API_KEY MYSQL_PASSWORD MYSQL_ROOT_PASSWORD
    [[ -f "$COLLECTOR/Dockerfile" ]] || fail "Collector submodule missing. Run: git submodule update --init --recursive"
    log "Starting Docker Compose profile 'real' (first build can take several minutes)"
    docker compose --env-file "$ENV_FILE" -f "$ROOT/compose.yaml" --profile real up -d --build --wait --wait-timeout 300
    ;;
esac

if [[ "$MODE" == "real" ]]; then
    # The management port is not published from the container; `compose up --wait` above already waited
    # for the backend healthcheck (actuator readiness on 8090 inside the container).
    log "Backend is healthy (container healthcheck passed): http://localhost:8080/api/v1"
else
    wait_url "http://127.0.0.1:$MANAGEMENT_PORT/actuator/health/readiness" 180
    log "Backend is ready: http://localhost:$MANAGEMENT_PORT/actuator/health/readiness"
fi
[[ -n "${KAKAO_MAPS_JAVASCRIPT_KEY:-}" ]] || log "WARNING: KAKAO_MAPS_JAVASCRIPT_KEY is empty. The map area shows an unavailable notice; lists/search still work."
[[ -n "${KAKAO_MAPS_REST_API_KEY:-}" ]] || log "WARNING: KAKAO_MAPS_REST_API_KEY is empty. Place search and routing return 503."

MANAGEMENT_SERVER_PORT="$MANAGEMENT_PORT" start_bg web "$WEB" npm run dev
wait_url "http://127.0.0.1:5173" 90
log "Started $MODE mode: http://localhost:5173"
log "Stop with: scripts/stop-local.sh"
