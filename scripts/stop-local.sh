#!/usr/bin/env bash
# Stops processes started by scripts/start-local.sh. Docker volumes (MySQL data) are preserved.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.local"
ENV_FILE="$ROOT/.env"

# Stop a process and all of its descendants (npm -> sh -> node, nohup -> java).
kill_tree() {
    local parent="$1" signal="$2" child
    for child in $(pgrep -P "$parent" 2>/dev/null); do kill_tree "$child" "$signal"; done
    kill "-$signal" "$parent" 2>/dev/null
}

for name in web backend collector; do
    pid_file="$STATE/$name.pid"
    [[ -f "$pid_file" ]] || continue
    pid="$(cat "$pid_file")"
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
        kill_tree "$pid" TERM
        for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
        kill -0 "$pid" 2>/dev/null && kill_tree "$pid" KILL
        echo "[stop-local] stopped $name (pid $pid)"
    fi
    rm -f "$pid_file"
done

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    env_args=()
    [[ -f "$ENV_FILE" ]] && env_args=(--env-file "$ENV_FILE")
    # Interpolation guards (:?) require the passwords; placeholders are fine for `stop`.
    MYSQL_PASSWORD="${MYSQL_PASSWORD:-unused}" MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-unused}" \
        docker compose "${env_args[@]}" -f "$ROOT/compose.yaml" --profile real stop >/dev/null 2>&1 \
        && echo "[stop-local] docker compose services stopped (volumes preserved)"
fi
echo "[stop-local] done"
