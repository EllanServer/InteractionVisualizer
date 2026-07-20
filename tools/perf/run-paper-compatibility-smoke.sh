#!/usr/bin/env bash
set -euo pipefail

readonly EXPECTED_PAPER_VERSION="26.2"
readonly EXPECTED_PAPER_BUILD_ID="62"
readonly EXPECTED_PAPER_CHANNEL="BETA"
readonly EXPECTED_PAPER_SHA256="597cb54eef27b318dfb7daef924f9bbe2816e6b0547f0f861b15b42337b6ccd5"

fail() {
  echo "$*" >&2
  exit 1
}

validate_server_log() {
  local log_path="$1"

  grep -Fq -- "[InteractionVisualizer] Enabled for Paper 26.2!" "$log_path" \
    || { echo "Paper 26.2 enable confirmation is missing" >&2; return 1; }
  if grep -Eqi -- "ClientTextDisplayBridge|exact packet-only legacy text displays" "$log_path"; then
    echo "ClientTextDisplayBridge initialization warning or exception was logged" >&2
    return 1
  fi
  if grep -Eqi -- "Error occurred while enabling InteractionVisualizer|Could not load.*InteractionVisualizer|Exception encountered when loading plugin.*InteractionVisualizer" "$log_path"; then
    echo "InteractionVisualizer failed during plugin loading or enable" >&2
    return 1
  fi
  grep -Fq -- "Stopping server" "$log_path" \
    || { echo "Paper did not log a normal stop" >&2; return 1; }
  grep -Fq -- "[InteractionVisualizer] Disabled; all display entities removed." "$log_path" \
    || { echo "InteractionVisualizer disable confirmation is missing" >&2; return 1; }

  python3 - "$log_path" <<'PY'
import json
from pathlib import Path
import sys

marker = "IV_PERF_SHUTDOWN "
records = []
for line in Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").splitlines():
    if marker in line:
        records.append(json.loads(line.split(marker, 1)[1]))
if len(records) != 1:
    raise SystemExit(f"expected exactly one shutdown audit, found {len(records)}")
if records[0].get("totalRetained") != 0:
    raise SystemExit(f"shutdown audit retained state: {records[0]!r}")
PY
}

self_test() {
  local test_root
  test_root="$(mktemp -d)"
  trap 'rm -rf -- "$test_root"' RETURN

  cat > "$test_root/pass.log" <<'EOF'
[Server thread/INFO]: [InteractionVisualizer] Enabled for Paper 26.2!
[Server thread/INFO]: Stopping server
[Server thread/INFO]: [InteractionVisualizer] IV_PERF_SHUTDOWN {"schedulerTasks":0,"totalRetained":0}
[Server thread/INFO]: [InteractionVisualizer] Disabled; all display entities removed.
EOF
  validate_server_log "$test_root/pass.log"

  cp "$test_root/pass.log" "$test_root/bridge-failure.log"
  printf '%s\n' '[Server thread/ERROR]: ClientTextDisplayBridge initialization failed' \
    >> "$test_root/bridge-failure.log"
  if validate_server_log "$test_root/bridge-failure.log" 2>/dev/null; then
    fail "self-test accepted a bridge initialization failure"
  fi

  cat > "$test_root/retained.log" <<'EOF'
[Server thread/INFO]: [InteractionVisualizer] Enabled for Paper 26.2!
[Server thread/INFO]: Stopping server
[Server thread/INFO]: [InteractionVisualizer] IV_PERF_SHUTDOWN {"schedulerTasks":1,"totalRetained":1}
[Server thread/INFO]: [InteractionVisualizer] Disabled; all display entities removed.
EOF
  if validate_server_log "$test_root/retained.log" 2>/dev/null; then
    fail "self-test accepted retained shutdown state"
  fi

  printf '%s\n' '{"passed":true,"checks":["success-log","bridge-failure","retained-shutdown"]}'
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
  exit 0
fi
if [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--self-test]" >&2
  exit 64
fi
case "$(uname -s)" in
  Linux*) ;;
  *) fail "The real Paper compatibility smoke requires Linux FIFO semantics; use --self-test on this platform" ;;
esac

for variable in PAPER_COMPAT_PAPER_JAR PAPER_COMPAT_PLUGIN_JAR PAPER_COMPAT_OUTPUT_ROOT; do
  [[ -n "${!variable:-}" ]] || { echo "$variable is required" >&2; exit 64; }
done
for command_name in java python3 sha256sum mkfifo unzip; do
  command -v "$command_name" >/dev/null \
    || { echo "$command_name is required" >&2; exit 69; }
done

paper_jar="$(realpath "$PAPER_COMPAT_PAPER_JAR")"
plugin_jar="$(realpath "$PAPER_COMPAT_PLUGIN_JAR")"
output_root="$(realpath -m "$PAPER_COMPAT_OUTPUT_ROOT")"
server_port="${PAPER_COMPAT_SERVER_PORT:-25566}"
startup_timeout_seconds="${PAPER_COMPAT_STARTUP_TIMEOUT_SECONDS:-300}"
shutdown_timeout_seconds="${PAPER_COMPAT_SHUTDOWN_TIMEOUT_SECONDS:-120}"
paper_commit_sha="${PAPER_COMPAT_PAPER_COMMIT_SHA:-unknown}"
source_sha="${PAPER_COMPAT_SOURCE_SHA:-unknown}"

[[ -f "$paper_jar" ]] || { echo "Paper jar is missing: $paper_jar" >&2; exit 66; }
[[ -f "$plugin_jar" ]] || { echo "Plugin jar is missing: $plugin_jar" >&2; exit 66; }
[[ "$server_port" =~ ^[0-9]+$ ]] && (( server_port >= 1 && server_port <= 65535 )) \
  || { echo "PAPER_COMPAT_SERVER_PORT must be between 1 and 65535" >&2; exit 64; }
for timeout_value in "$startup_timeout_seconds" "$shutdown_timeout_seconds"; do
  [[ "$timeout_value" =~ ^[0-9]+$ ]] && (( timeout_value >= 10 && timeout_value <= 600 )) \
    || { echo "Compatibility smoke timeouts must be between 10 and 600 seconds" >&2; exit 64; }
done

paper_sha256="$(sha256sum "$paper_jar" | awk '{print $1}')"
[[ "$paper_sha256" == "$EXPECTED_PAPER_SHA256" ]] \
  || fail "Paper build $EXPECTED_PAPER_BUILD_ID SHA-256 mismatch: $paper_sha256"
plugin_sha256="$(sha256sum "$plugin_jar" | awk '{print $1}')"
script_sha256="$(sha256sum "${BASH_SOURCE[0]}" | awk '{print $1}')"

[[ ! -e "$output_root" ]] || fail "Compatibility output already exists: $output_root"
mkdir -p "$output_root"
runtime_directory="$output_root/runtime"
server_log="$output_root/server.log"
manifest_path="$output_root/compatibility-manifest.json"
console_fifo="$runtime_directory/console.pipe"
mkdir -p "$runtime_directory/plugins/InteractionVisualizer" "$runtime_directory/plugins/bStats"
cp "$paper_jar" "$runtime_directory/server.jar"
cp "$plugin_jar" "$runtime_directory/plugins/InteractionVisualizer.jar"
unzip -p "$plugin_jar" config.yml > "$runtime_directory/plugins/InteractionVisualizer/config.yml"
python3 - "$runtime_directory/plugins/InteractionVisualizer/config.yml" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
for old, new in (
    ("  Updater: true", "  Updater: false"),
    ("  DownloadLanguageFiles: true", "  DownloadLanguageFiles: false"),
):
    if text.count(old) != 1:
        raise SystemExit(f"expected exactly one production config entry: {old!r}")
    text = text.replace(old, new)
path.write_text(text, encoding="utf-8")
PY

cat > "$runtime_directory/plugins/bStats/config.yml" <<'EOF'
enabled: false
serverUuid: 00000000-0000-0000-0000-000000000000
logFailedRequests: false
logSentData: false
logResponseStatusText: false
EOF
cat > "$runtime_directory/eula.txt" <<'EOF'
eula=true
EOF
cat > "$runtime_directory/server.properties" <<EOF
allow-flight=true
enable-command-block=false
enable-jmx-monitoring=false
enable-query=false
enable-rcon=false
enable-status=false
enforce-secure-profile=false
generate-structures=false
level-name=compatibility-world
level-seed=interactionvisualizer-paper-26-2-compatibility
level-type=minecraft:flat
max-players=1
max-tick-time=-1
motd=InteractionVisualizer Paper 26.2 compatibility smoke
online-mode=false
server-ip=127.0.0.1
server-port=$server_port
simulation-distance=2
spawn-animals=false
spawn-monsters=false
spawn-npcs=false
spawn-protection=0
sync-chunk-writes=false
view-distance=2
white-list=false
EOF

server_pid=""
console_open=0
cleanup_complete=0

wait_for_pid_exit() {
  local pid="$1"
  local timeout_seconds="$2"
  local deadline=$(( SECONDS + timeout_seconds ))
  while (( SECONDS < deadline )); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 1
  done
  ! kill -0 "$pid" 2>/dev/null
}

terminate_pid_bounded() {
  local pid="$1"
  kill -0 "$pid" 2>/dev/null || return 0
  kill -TERM "$pid" 2>/dev/null || true
  if ! wait_for_pid_exit "$pid" 10; then
    kill -KILL "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
}

prune_runtime_payload() {
  rm -rf -- \
    "$runtime_directory/cache" \
    "$runtime_directory/libraries" \
    "$runtime_directory/logs" \
    "$runtime_directory/versions" \
    "$runtime_directory/compatibility-world" \
    "$runtime_directory/compatibility-world_nether" \
    "$runtime_directory/compatibility-world_the_end" \
    "$runtime_directory/plugins/.paper-remapped"
  rm -f -- \
    "$runtime_directory/server.jar" \
    "$runtime_directory/plugins/InteractionVisualizer.jar"
}

cleanup() {
  local exit_status=$?
  if [[ "$cleanup_complete" == 1 ]]; then
    return "$exit_status"
  fi
  cleanup_complete=1
  set +e
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    if [[ "$console_open" == 1 ]]; then
      printf 'stop\n' >&3
    fi
    wait_for_pid_exit "$server_pid" 30 || terminate_pid_bounded "$server_pid"
  fi
  if [[ "$console_open" == 1 ]]; then
    exec 3>&-
    console_open=0
  fi
  rm -f -- "$console_fifo"
  prune_runtime_payload
  return "$exit_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_for_log() {
  local pattern="$1"
  local timeout_seconds="$2"
  local deadline=$(( SECONDS + timeout_seconds ))
  while (( SECONDS < deadline )); do
    if [[ -f "$server_log" ]] && grep -Fq -- "$pattern" "$server_log"; then
      return 0
    fi
    if [[ -n "$server_pid" ]] && ! kill -0 "$server_pid" 2>/dev/null; then
      echo "Paper exited while waiting for: $pattern" >&2
      tail -n 200 "$server_log" >&2 || true
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for Paper log: $pattern" >&2
  tail -n 200 "$server_log" >&2 || true
  return 1
}

started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mkfifo "$console_fifo"
exec 3<>"$console_fifo"
console_open=1
(
  cd "$runtime_directory"
  exec java -Xms512M -Xmx1G -Dfile.encoding=UTF-8 -jar server.jar --nogui < console.pipe
) > "$server_log" 2>&1 &
server_pid=$!

wait_for_log "[InteractionVisualizer] Enabled for Paper 26.2!" "$startup_timeout_seconds"
wait_for_log "Done (" "$startup_timeout_seconds"
printf 'stop\n' >&3

if ! wait_for_pid_exit "$server_pid" "$shutdown_timeout_seconds"; then
  fail "Paper did not stop within $shutdown_timeout_seconds seconds"
fi
set +e
wait "$server_pid"
server_exit_code=$?
set -e
server_pid=""
exec 3>&-
console_open=0
rm -f -- "$console_fifo"
[[ "$server_exit_code" == 0 ]] || fail "Paper exited with status $server_exit_code"
validate_server_log "$server_log"
completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

python3 - "$manifest_path" "$paper_sha256" "$plugin_sha256" "$script_sha256" \
  "$paper_commit_sha" "$source_sha" "$started_at" "$completed_at" "$server_exit_code" <<'PY'
import json
from pathlib import Path
import sys

(
    output,
    paper_sha,
    plugin_sha,
    script_sha,
    paper_commit_sha,
    source_sha,
    started_at,
    completed_at,
    server_exit_code,
) = sys.argv[1:]
manifest = {
    "schemaVersion": 1,
    "result": "passed",
    "paper": {
        "project": "paper",
        "version": "26.2",
        "buildId": 62,
        "channel": "BETA",
        "sha256": paper_sha,
        "commitSha": paper_commit_sha,
    },
    "plugin": {
        "artifact": "current-production-jar",
        "sha256": plugin_sha,
        "sourceSha": source_sha,
    },
    "harness": {
        "sha256": script_sha,
        "clientRequired": False,
        "bridgeEagerInitialization": True,
        "startupTimeoutSeconds": int(__import__("os").environ.get(
            "PAPER_COMPAT_STARTUP_TIMEOUT_SECONDS", "300"
        )),
        "shutdownTimeoutSeconds": int(__import__("os").environ.get(
            "PAPER_COMPAT_SHUTDOWN_TIMEOUT_SECONDS", "120"
        )),
    },
    "assertions": {
        "enabledForPaper26_2": True,
        "clientTextDisplayBridgeInitializationClean": True,
        "normalStop": True,
        "shutdownTotalRetained": 0,
        "serverExitCode": int(server_exit_code),
    },
    "serverLog": "server.log",
    "startedAt": started_at,
    "completedAt": completed_at,
}
Path(output).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY
