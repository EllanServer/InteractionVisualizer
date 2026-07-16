#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
protocol_client_source="$script_directory/phase2-protocol-client.js"
protocol_trace_analyzer_source="$script_directory/analyze-phase2-protocol-trace.js"

for variable in \
  COMPARE_PLUGIN_JAR COMPARE_DRIVER_JAR COMPARE_PAPER_JAR COMPARE_CONFIG_FILE \
  COMPARE_CLIENT_ROOT \
  COMPARE_OUTPUT_ROOT COMPARE_RUN_ID COMPARE_SCENARIO COMPARE_VARIANT \
  COMPARE_RUNTIME_PROFILE \
  COMPARE_SCENE_SIZE COMPARE_WARMUP_SECONDS COMPARE_SETTLE_SECONDS \
  COMPARE_MEASURE_SECONDS; do
  [[ -n "${!variable:-}" ]] || { echo "$variable is required" >&2; exit 64; }
done

plugin_jar="$(realpath "$COMPARE_PLUGIN_JAR")"
driver_jar="$(realpath "$COMPARE_DRIVER_JAR")"
paper_jar="$(realpath "$COMPARE_PAPER_JAR")"
config_file="$(realpath "$COMPARE_CONFIG_FILE")"
client_root="$(realpath "$COMPARE_CLIENT_ROOT")"
output_root="$(realpath -m "$COMPARE_OUTPUT_ROOT")"
run_id="$COMPARE_RUN_ID"
scenario="$COMPARE_SCENARIO"
variant="$COMPARE_VARIANT"
runtime_profile="$COMPARE_RUNTIME_PROFILE"
scene_size="$COMPARE_SCENE_SIZE"
warmup_seconds="$COMPARE_WARMUP_SECONDS"
settle_seconds="$COMPARE_SETTLE_SECONDS"
measure_seconds="$COMPARE_MEASURE_SECONDS"
# This only prevents the synthetic observer from being kicked while the
# official upstream stalls; it does not alter the server's 20 TPS target.
network_timeout_seconds=300
server_port="${COMPARE_SERVER_PORT:-25565}"
protocol_trace_enabled="${COMPARE_PROTOCOL_TRACE_ENABLED:-0}"
protocol_trace_max_events="${COMPARE_PROTOCOL_TRACE_MAX_EVENTS:-500000}"
protocol_trace_packet_allowlist="${COMPARE_PROTOCOL_TRACE_PACKET_ALLOWLIST:-}"
protocol_trace_aggregate_packet_allowlist="${COMPARE_PROTOCOL_TRACE_AGGREGATE_PACKET_ALLOWLIST:-}"

[[ "$variant" == A || "$variant" == B ]] \
  || { echo "COMPARE_VARIANT must be A or B" >&2; exit 64; }
case "$runtime_profile" in
  legacy-parity|optimized-candidate) ;;
  *)
    echo "COMPARE_RUNTIME_PROFILE must be legacy-parity or optimized-candidate" >&2
    exit 64
    ;;
esac
[[ "$scenario" == dropped-items || "$scenario" == block-active ]] \
  || { echo "Unsupported comparison scenario: $scenario" >&2; exit 64; }
[[ "$protocol_trace_enabled" == 0 || "$protocol_trace_enabled" == 1 ]] \
  || { echo "COMPARE_PROTOCOL_TRACE_ENABLED must be 0 or 1" >&2; exit 64; }
[[ "$protocol_trace_max_events" =~ ^[0-9]+$ ]] \
  && (( protocol_trace_max_events >= 100000 && protocol_trace_max_events <= 1000000 )) \
  || { echo "COMPARE_PROTOCOL_TRACE_MAX_EVENTS must be between 100000 and 1000000" >&2; exit 64; }
[[ -z "$protocol_trace_packet_allowlist" \
    || "$protocol_trace_packet_allowlist" =~ ^[A-Za-z0-9_-]+(,[A-Za-z0-9_-]+)*$ ]] \
  || { echo "COMPARE_PROTOCOL_TRACE_PACKET_ALLOWLIST must be a comma-separated packet-name list" >&2; exit 64; }
[[ -z "$protocol_trace_aggregate_packet_allowlist" \
    || "$protocol_trace_aggregate_packet_allowlist" =~ ^[A-Za-z0-9_-]+(,[A-Za-z0-9_-]+)*$ ]] \
  || { echo "COMPARE_PROTOCOL_TRACE_AGGREGATE_PACKET_ALLOWLIST must be a comma-separated packet-name list" >&2; exit 64; }
declare -A protocol_trace_packet_modes=()
if [[ -n "$protocol_trace_packet_allowlist" ]]; then
  IFS=',' read -ra protocol_trace_capture_packets <<< "$protocol_trace_packet_allowlist"
  for packet_name in "${protocol_trace_capture_packets[@]}"; do
    protocol_trace_packet_modes["${packet_name,,}"]=capture
  done
fi
if [[ -n "$protocol_trace_aggregate_packet_allowlist" ]]; then
  IFS=',' read -ra protocol_trace_aggregate_packets <<< "$protocol_trace_aggregate_packet_allowlist"
  for packet_name in "${protocol_trace_aggregate_packets[@]}"; do
    normalized_packet_name="${packet_name,,}"
    [[ -z "${protocol_trace_packet_modes[$normalized_packet_name]+x}" ]] \
      || { echo "Protocol trace capture and aggregate allowlists overlap: $normalized_packet_name" >&2; exit 64; }
    protocol_trace_packet_modes["$normalized_packet_name"]=aggregate
  done
fi
[[ "$scene_size" =~ ^[0-9]+$ ]] && (( scene_size >= 1 && scene_size <= 4096 )) \
  || { echo "COMPARE_SCENE_SIZE must be between 1 and 4096" >&2; exit 64; }
for duration in "$warmup_seconds" "$settle_seconds" "$measure_seconds"; do
  [[ "$duration" =~ ^[0-9]+$ ]] || { echo "Durations must be integers" >&2; exit 64; }
done
(( warmup_seconds >= 10 && settle_seconds >= 5 && measure_seconds >= 10 )) \
  || { echo "Warmup/settle/measurement windows are too short" >&2; exit 64; }
[[ "$run_id" =~ ^[A-Za-z0-9_.-]+$ ]] \
  || { echo "COMPARE_RUN_ID contains unsupported characters" >&2; exit 64; }

for file in "$plugin_jar" "$driver_jar" "$paper_jar" "$config_file" \
  "$client_root/client-build-manifest.json" "$protocol_client_source"; do
  [[ -f "$file" ]] || { echo "Required file is missing: $file" >&2; exit 66; }
done
if [[ "$protocol_trace_enabled" == 1 && ! -f "$protocol_trace_analyzer_source" ]]; then
  echo "Semantic protocol trace analyzer source is missing" >&2
  exit 66
fi
[[ -d "$client_root/node-minecraft-protocol" ]] \
  || { echo "Prepared node-minecraft-protocol directory is missing" >&2; exit 66; }
command -v taskset >/dev/null \
  || { echo "taskset is required for server/client CPU isolation" >&2; exit 69; }
read -r available_cpu_count server_cpu_set client_cpu_set < <(
  python3 - <<'PY'
import os

cpus = sorted(os.sched_getaffinity(0))
if len(cpus) < 3:
    raise SystemExit(
        f"At least three available logical CPUs are required for isolated comparison; found {cpus}"
    )
print(len(cpus), ",".join(map(str, cpus[:-1])), cpus[-1])
PY
)

run_directory="$output_root/$run_id"
[[ ! -e "$run_directory" ]] \
  || { echo "Run output already exists: $run_directory" >&2; exit 73; }
mkdir -p \
  "$run_directory/plugins/InteractionVisualizer" \
  "$run_directory/plugins/InteractionVisualizerRuntimeCompare" \
  "$run_directory/plugins/bStats"

server_log="$run_directory/server.log"
client_log="$run_directory/protocol-client.log"
client_ready="$run_directory/protocol-client-ready.json"
client_state="$run_directory/protocol-client-state.json"
console_fifo="$run_directory/console.pipe"
metrics_path="$run_directory/iv-compare.json"
protocol_trace_path="$run_directory/$run_id.protocol-trace.json"
protocol_trace_analysis_path="$run_directory/$run_id.protocol-trace-analysis.json"
jvm_log_name="jvm-gc-safepoint.log"

cp "$paper_jar" "$run_directory/server.jar"
cp "$plugin_jar" "$run_directory/plugins/InteractionVisualizer.jar"
cp "$driver_jar" "$run_directory/plugins/InteractionVisualizerRuntimeCompare.jar"
cp "$config_file" "$run_directory/plugins/InteractionVisualizer/config.yml"
cmp -s "$config_file" "$run_directory/plugins/InteractionVisualizer/config.yml" \
  || { echo "Canonical config changed while copying" >&2; exit 1; }
python3 - "$run_directory/plugins/InteractionVisualizer/config.yml" \
  "$runtime_profile" <<'PY'
from pathlib import Path
import re
import sys

path, runtime_profile = sys.argv[1:]
text = Path(path).read_text(encoding="utf-8")
values = {}
stack = []
for raw_line in text.splitlines():
    if not raw_line.strip() or raw_line.lstrip().startswith(("#", "-")):
        continue
    match = re.match(r"^(\s*)([^:#][^:]*):(?:\s*(.*?))?\s*$", raw_line)
    if match is None:
        continue
    indent = len(match.group(1))
    key = match.group(2).strip()
    value = (match.group(3) or "").strip()
    while stack and indent <= stack[-1][0]:
        stack.pop()
    path = ".".join([entry[1] for entry in stack] + [key])
    if value:
        values[path] = value
    else:
        stack.append((indent, key))

optimized = runtime_profile == "optimized-candidate"
if runtime_profile not in {"legacy-parity", "optimized-candidate"}:
    raise SystemExit(f"Unsupported runtime profile: {runtime_profile!r}")
expected = {
    "Entities.Item.Options.UpdateRate": "20",
    "Entities.Item.Options.LabelYOffset": "0.8",
    "Entities.Item.Options.VisibilityCulling.Enabled": "false",
    "Entities.Item.Options.VisibilityRateLimit.Enabled": "false",
    "Entities.Item.Options.DespawnTicks": "6000",
    "Settings.HideIfViewObstructed": "false",
    "Settings.Performance.VirtualItems.StaticAnchorDuringAnimation": "false",
    "Settings.Performance.VirtualItems.PacketOnlyStatic": str(optimized).lower(),
    "Settings.Performance.VisibilityRateLimit.Enabled": "false",
    "Settings.Performance.BlockUpdates.EventDriven": str(optimized).lower(),
    "Settings.Performance.BlockUpdates.MaxDirtyPerTick": "64",
    "Options.Updater": "false",
    "Options.DownloadLanguageFiles": "false",
}
for path, expected_value in expected.items():
    if values.get(path) != expected_value:
        raise SystemExit(
            f"Canonical config mismatch for {path}: "
            f"{values.get(path)!r} != {expected_value!r}"
        )
PY

cat > "$run_directory/plugins/bStats/config.yml" <<'EOF'
enabled: false
serverUuid: 00000000-0000-0000-0000-000000000000
logFailedRequests: false
logSentData: false
logResponseStatusText: false
EOF

cat > "$run_directory/eula.txt" <<'EOF'
eula=true
EOF

cat > "$run_directory/server.properties" <<EOF
allow-flight=true
enable-command-block=false
enable-jmx-monitoring=false
enable-query=false
enable-rcon=false
enable-status=true
enforce-secure-profile=false
force-gamemode=true
gamemode=creative
generate-structures=false
hardcore=false
difficulty=peaceful
level-name=compare-world
level-seed=interactionvisualizer-upstream-comparison
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
max-players=1
max-tick-time=-1
motd=InteractionVisualizer upstream comparison
network-compression-threshold=256
online-mode=false
server-ip=127.0.0.1
server-port=$server_port
simulation-distance=4
spawn-animals=false
spawn-monsters=false
spawn-npcs=false
spawn-protection=0
sync-chunk-writes=false
view-distance=4
white-list=false
EOF

cat > "$run_directory/spigot.yml" <<EOF
settings:
  timeout-time: $network_timeout_seconds
EOF

plugin_sha256="$(sha256sum "$plugin_jar" | awk '{print $1}')"
driver_sha256="$(sha256sum "$driver_jar" | awk '{print $1}')"
paper_sha256="$(sha256sum "$paper_jar" | awk '{print $1}')"
config_sha256="$(sha256sum "$run_directory/plugins/InteractionVisualizer/config.yml" | awk '{print $1}')"
client_manifest_sha256="$(sha256sum "$client_root/client-build-manifest.json" | awk '{print $1}')"
script_sha256="$(sha256sum "${BASH_SOURCE[0]}" | awk '{print $1}')"

jvm_arguments=(
  -Xms2G
  -Xmx2G
  -XX:+UseG1GC
  -XX:+AlwaysPreTouch
  "-Xlog:gc*=info,safepoint=info:file=$jvm_log_name:time,uptime,level,tags:filecount=0"
  -Dfile.encoding=UTF-8
)
jvm_fingerprint="$(IFS=' '; printf '%s' "${jvm_arguments[*]}")"
jvm_sha256="$(printf '%s' "$jvm_fingerprint" | sha256sum | awk '{print $1}')"

server_pid=""
client_pid=""
console_open=0
cleanup_complete=0

prune_runtime_payload() {
  rm -rf -- \
    "$run_directory/cache" \
    "$run_directory/libraries" \
    "$run_directory/logs" \
    "$run_directory/versions" \
    "$run_directory/compare-world" \
    "$run_directory/compare-world_nether" \
    "$run_directory/compare-world_the_end" \
    "$run_directory/plugins/.paper-remapped"
  rm -f -- \
    "$run_directory/server.jar" \
    "$run_directory/plugins/InteractionVisualizer.jar" \
    "$run_directory/plugins/InteractionVisualizerRuntimeCompare.jar"
}

cleanup() {
  local exit_status=$?
  if [[ "$cleanup_complete" == 1 ]]; then
    return "$exit_status"
  fi
  cleanup_complete=1
  set +e
  if [[ -n "$client_pid" ]] && kill -0 "$client_pid" 2>/dev/null; then
    kill -TERM "$client_pid"
    wait "$client_pid"
  fi
  if [[ "$console_open" == 1 ]]; then
    printf 'stop\n' >&3
  fi
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    for _ in $(seq 1 60); do
      kill -0 "$server_pid" 2>/dev/null || break
      sleep 1
    done
    kill -TERM "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
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
  for _ in $(seq 1 "$timeout_seconds"); do
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
  echo "Timed out waiting for server log: $pattern" >&2
  tail -n 200 "$server_log" >&2 || true
  return 1
}

wait_for_file() {
  local path="$1"
  local timeout_seconds="$2"
  for _ in $(seq 1 "$timeout_seconds"); do
    [[ -s "$path" ]] && return 0
    sleep 1
  done
  echo "Timed out waiting for file: $path" >&2
  return 1
}

send_console() {
  printf '%s\n' "$*" >&3
}

assert_client_alive() {
  if [[ -z "$client_pid" ]] || ! kill -0 "$client_pid" 2>/dev/null; then
    echo "Protocol client exited during the comparison run" >&2
    tail -n 200 "$client_log" >&2 || true
    [[ -z "$client_pid" ]] || wait "$client_pid" 2>/dev/null || true
    exit 1
  fi
}

mkfifo "$console_fifo"
exec 3<>"$console_fifo"
console_open=1
if [[ -n "${JAVA_TOOL_OPTIONS:-}" || -n "${_JAVA_OPTIONS:-}" || -n "${JDK_JAVA_OPTIONS:-}" ]]; then
  echo "Inherited Java option environment variables invalidate JVM parity" >&2
  exit 1
fi
(
  cd "$run_directory"
  exec taskset --cpu-list "$server_cpu_set" \
    java "${jvm_arguments[@]}" -jar server.jar --nogui < console.pipe
) > "$server_log" 2>&1 &
server_pid=$!

wait_for_log "Enabling InteractionVisualizer v2026.1.2.0" 300
wait_for_log "Enabling InteractionVisualizerRuntimeCompare v1.0" 120
wait_for_log "Done (" 300

python3 - "/proc/$server_pid/cmdline" "$run_directory/jvm-command-line.json" \
  "$server_pid" "$jvm_fingerprint" "$jvm_sha256" <<'PY'
from pathlib import Path
import hashlib
import json
import sys

proc_path, output_path, pid, expected_fingerprint, expected_sha = sys.argv[1:]
raw = Path(proc_path).read_bytes()
if not raw.endswith(b"\0"):
    raise SystemExit("live JVM command line is malformed")
args = [part.decode("utf-8") for part in raw[:-1].split(b"\0")]
jar_index = args.index("-jar")
jvm_args = args[1:jar_index]
actual_fingerprint = " ".join(jvm_args)
actual_sha = hashlib.sha256(actual_fingerprint.encode()).hexdigest()
if actual_fingerprint != expected_fingerprint or actual_sha != expected_sha:
    raise SystemExit("live JVM arguments differ from the comparison fingerprint")
Path(output_path).write_text(json.dumps({
    "schemaVersion": 1,
    "processId": int(pid),
    "jvmArguments": jvm_args,
    "jvmArgumentsFingerprint": actual_fingerprint,
    "jvmArgumentsSha256": actual_sha,
    "applicationArguments": args[jar_index:],
}, indent=2) + "\n", encoding="utf-8")
PY

send_console "difficulty peaceful"
send_console "gamerule minecraft:spawn_mobs false"
send_console "gamerule minecraft:advance_weather false"
send_console "gamerule minecraft:advance_time false"
send_console "gamerule minecraft:random_tick_speed 0"
send_console "weather clear"
send_console "time set day"

protocol_trace_environment=("PHASE2_PROTOCOL_TRACE_FILE=")
if [[ "$protocol_trace_enabled" == 1 ]]; then
  protocol_trace_environment=(
    "PHASE2_PROTOCOL_TRACE_FILE=$protocol_trace_path"
    "PHASE2_PROTOCOL_TRACE_MAX_EVENTS=$protocol_trace_max_events"
    "PHASE2_PROTOCOL_TRACE_PACKET_ALLOWLIST=$protocol_trace_packet_allowlist"
    "PHASE2_PROTOCOL_TRACE_AGGREGATE_PACKET_ALLOWLIST=$protocol_trace_aggregate_packet_allowlist"
  )
fi
env \
  "PHASE2_MC_PROTOCOL_MODULE=$client_root/node-minecraft-protocol" \
  PHASE2_SERVER_HOST=127.0.0.1 \
  "PHASE2_SERVER_PORT=$server_port" \
  PHASE2_CLIENT_USERNAME=IVBench \
  PHASE2_CLIENT_VERSION=26.1.2 \
  "PHASE2_CLIENT_KEEPALIVE_TIMEOUT_MS=$((network_timeout_seconds * 1000))" \
  "PHASE2_CLIENT_READY_FILE=$client_ready" \
  "PHASE2_CLIENT_STATE_FILE=$client_state" \
  "${protocol_trace_environment[@]}" \
  taskset --cpu-list "$client_cpu_set" \
  node "$protocol_client_source" > "$client_log" 2>&1 &
client_pid=$!
wait_for_file "$client_ready" 180
wait_for_log "IVBench joined the game" 60

python3 - "$server_pid" "$client_pid" "$available_cpu_count" \
  "$server_cpu_set" "$client_cpu_set" "$run_directory/cpu-affinity.json" <<'PY'
from pathlib import Path
import json
import os
import sys

server_pid, client_pid, available_count, server_text, client_text, output = sys.argv[1:]

def parse_cpu_set(value):
    return sorted(int(part) for part in value.split(",") if part)

expected_server = parse_cpu_set(server_text)
expected_client = parse_cpu_set(client_text)
actual_server = sorted(os.sched_getaffinity(int(server_pid)))
actual_client = sorted(os.sched_getaffinity(int(client_pid)))
if actual_server != expected_server:
    raise SystemExit(
        f"server CPU affinity mismatch: {actual_server!r} != {expected_server!r}"
    )
if actual_client != expected_client:
    raise SystemExit(
        f"client CPU affinity mismatch: {actual_client!r} != {expected_client!r}"
    )
if set(actual_server) & set(actual_client):
    raise SystemExit("server and protocol client CPU affinity overlap")
Path(output).write_text(json.dumps({
    "schemaVersion": 1,
    "availableCpuCount": int(available_count),
    "serverCpuSet": actual_server,
    "clientCpuSet": actual_client,
    "disjoint": True,
}, indent=2) + "\n", encoding="utf-8")
PY

send_console "op IVBench"
send_console "iv toggle itemstand all true IVBench"
send_console "iv toggle itemdrop all true IVBench"
send_console "iv toggle hologram all true IVBench"
sleep 2
assert_client_alive
trace_window_start_epoch_ms="$(python3 -c 'import time; print(time.time_ns() // 1_000_000)')"
send_console "ivcompare clear"
sleep 1
send_console "ivcompare setup $scenario $scene_size IVBench"
wait_for_log "IV_COMPARE_SCENE state=ready scenario=$scenario count=$scene_size player=IVBench" 180

sleep "$warmup_seconds"
sleep "$settle_seconds"

if [[ "$scenario" == block-active ]]; then
  expected_active_furnaces=$(( (scene_size / 5) * 3 ))
  remainder=$(( scene_size % 5 ))
  if (( remainder < 3 )); then
    expected_active_furnaces=$(( expected_active_furnaces + remainder ))
  else
    expected_active_furnaces=$(( expected_active_furnaces + 3 ))
  fi
else
  expected_active_furnaces=0
fi
assert_client_alive
send_console "ivcompare status"
wait_for_log "IV_COMPARE_STATUS collecting=false scenario=$scenario expected=$scene_size actual=$scene_size player=IVBench targetEnabled=true activeFurnaces=$expected_active_furnaces" 30
send_console "ivcompare start $run_id $variant $runtime_profile"
wait_for_log "IV_COMPARE_START label=$run_id variant=$variant runtimeProfile=$runtime_profile" 30
sleep "$measure_seconds"
send_console "ivcompare stop"
wait_for_log "IV_COMPARE {\"schemaVersion\":1,\"label\":\"$run_id\"" 60
trace_window_end_epoch_ms="$(python3 -c 'import time; print(time.time_ns() // 1_000_000)')"
assert_client_alive

driver_result="$run_directory/plugins/InteractionVisualizerRuntimeCompare/results/$run_id.json"
wait_for_file "$driver_result" 30
cp "$driver_result" "$metrics_path"

python3 - "$metrics_path" "$run_id" "$variant" "$scenario" "$scene_size" \
  "$measure_seconds" "$runtime_profile" <<'PY'
import json
import math
import sys

path, run_id, variant, scenario, scene_size_text, measure_text, runtime_profile = sys.argv[1:]
data = json.load(open(path, encoding="utf-8"))
scene_size = int(scene_size_text)
measure = int(measure_text)
optimized = runtime_profile == "optimized-candidate"
if runtime_profile not in {"legacy-parity", "optimized-candidate"}:
    raise SystemExit(f"unsupported runtime profile: {runtime_profile!r}")
expected = {
    "schemaVersion": 1,
    "label": run_id,
    "variant": variant,
    "scenario": scenario,
    "expectedSceneSize": scene_size,
    "actualSceneSize": scene_size,
    "observer": "IVBench",
    "observerOnline": True,
    "targetVersion": "2026.1.2.0",
    "targetEnabled": True,
    "boundaryTickSamplesDiscarded": 1,
    "droppedTickSamples": 0,
}
for key, value in expected.items():
    if data.get(key) != value:
        raise SystemExit(f"comparison result mismatch for {key}: {data.get(key)!r} != {value!r}")
expected_requested_flags = {
    "packetOnlyStatic": optimized,
    "eventDrivenBlockUpdates": optimized,
}
expected_effective_flags = {
    "packetOnlyStatic": {
        "status": "unsupported-legacy" if variant == "A" else "runtime-field",
        "value": None if variant == "A" else optimized,
        "field": "packetOnlyStaticVirtualItems",
    },
    "eventDrivenBlockUpdates": {
        "status": "unsupported-legacy" if variant == "A" else "runtime-field",
        "value": None if variant == "A" else optimized,
        "field": "eventDrivenBlockUpdates",
    },
}
if data.get("requestedFlags") != expected_requested_flags:
    raise SystemExit(
        f"comparison requested flags mismatch: "
        f"{data.get('requestedFlags')!r} != {expected_requested_flags!r}"
    )
if data.get("effectiveFlags") != expected_effective_flags:
    raise SystemExit(
        f"comparison effective flags mismatch: "
        f"{data.get('effectiveFlags')!r} != {expected_effective_flags!r}"
    )
allows_upstream_saturation = (
    measure >= 60 and scenario == "block-active" and variant == "A"
)
# The stop command is handled on the saturated main thread, so its wall-clock
# acknowledgement can trail the requested window without invalidating it.
maximum_seconds = measure + (30 if allows_upstream_saturation else 3)
if data.get("seconds", 0) < measure - 2 or data.get("seconds", 0) > maximum_seconds:
    raise SystemExit(f"comparison measurement duration is invalid: {data.get('seconds')}")
if data.get("tickSamples", 0) <= 0 or data.get("observedTps", 0) <= 0:
    raise SystemExit("comparison produced no valid tick samples")
if allows_upstream_saturation:
    # Sustained block load is allowed to expose the official upstream's
    # throughput ceiling. The rewritten candidate remains subject to the
    # healthy-20-TPS guard, and the upstream sample must still contain enough
    # tick observations for stable percentile estimates.
    if data["observedTps"] > 20.5:
        raise SystemExit(
            f"comparison exceeded the server TPS ceiling: {data['observedTps']}"
        )
    minimum_tick_samples = 100
else:
    minimum_tps = 19.9 if measure >= 60 else 19.5
    if not minimum_tps <= data["observedTps"] <= 20.5:
        raise SystemExit(
            f"comparison did not remain in the healthy 20 TPS regime "
            f"({minimum_tps}..20.5): {data['observedTps']}"
        )
    minimum_tick_samples = (
        math.floor(data["seconds"] * minimum_tps)
        - data["boundaryTickSamplesDiscarded"]
        - 1
    )
if data["tickSamples"] < minimum_tick_samples:
    raise SystemExit(f"comparison recorded too few tick samples: {data['tickSamples']}")
for key in ("msptP50", "msptP95", "msptP99", "msptP999", "msptMax", "msptMean"):
    value = data.get(key)
    if not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise SystemExit(f"invalid {key}: {value!r}")

composition_fields = (
    "furnaceBlocks",
    "blastFurnaceBlocks",
    "smokerBlocks",
    "beehiveBlocks",
    "beeNestBlocks",
)
if scenario == "block-active":
    pattern = composition_fields
    expected_composition = {field: 0 for field in composition_fields}
    for index in range(scene_size):
        expected_composition[pattern[index % len(pattern)]] += 1
    expected_active = (
        expected_composition["furnaceBlocks"]
        + expected_composition["blastFurnaceBlocks"]
        + expected_composition["smokerBlocks"]
    )
else:
    expected_composition = {field: 0 for field in composition_fields}
    expected_active = 0
for field, value in expected_composition.items():
    if data.get(field) != value:
        raise SystemExit(
            f"comparison scene composition mismatch for {field}: "
            f"{data.get(field)!r} != {value!r}"
        )
if data.get("activeFurnaces") != expected_active:
    raise SystemExit(
        f"comparison active furnace mismatch: "
        f"{data.get('activeFurnaces')!r} != {expected_active!r}"
    )
PY

runtime_config_sha256="$(sha256sum "$run_directory/plugins/InteractionVisualizer/config.yml" | awk '{print $1}')"

python3 - "$run_directory/run-manifest.json" "$run_id" "$scenario" "$variant" \
  "$scene_size" "$warmup_seconds" "$settle_seconds" "$measure_seconds" \
  "$plugin_sha256" "$driver_sha256" "$paper_sha256" "$config_sha256" \
  "$runtime_config_sha256" "$client_manifest_sha256" "$script_sha256" "$jvm_sha256" \
  "$protocol_trace_enabled" "$protocol_trace_packet_allowlist" \
  "$protocol_trace_aggregate_packet_allowlist" \
  "$trace_window_start_epoch_ms" "$trace_window_end_epoch_ms" \
  "$available_cpu_count" "$server_cpu_set" "$client_cpu_set" \
  "$runtime_profile" "$network_timeout_seconds" "$metrics_path" <<'PY'
from pathlib import Path
import json
import sys

(
    output, run_id, scenario, variant, scene_size, warmup, settle, measure,
    plugin_sha, driver_sha, paper_sha, config_sha, runtime_config_sha,
    client_sha, script_sha, jvm_sha,
    trace_enabled, trace_packet_allowlist, trace_aggregate_packet_allowlist,
    trace_window_start_epoch_ms, trace_window_end_epoch_ms,
    available_cpu_count, server_cpu_set, client_cpu_set,
    runtime_profile, network_timeout_seconds, metrics_path,
) = sys.argv[1:]
metrics = json.load(open(metrics_path, encoding="utf-8"))
Path(output).write_text(json.dumps({
    "schemaVersion": 1,
    "runId": run_id,
    "scenario": scenario,
    "variant": variant,
    "variantMeaning": "official-upstream" if variant == "A" else "rewritten-candidate",
    "runtimeProfile": runtime_profile,
    "networkTimeoutSeconds": int(network_timeout_seconds),
    "requestedFlags": metrics["requestedFlags"],
    "effectiveFlags": metrics["effectiveFlags"],
    "sceneSize": int(scene_size),
    "warmupSeconds": int(warmup),
    "settleSeconds": int(settle),
    "measureSeconds": int(measure),
    "artifactSha256": plugin_sha,
    "driverSha256": driver_sha,
    "paperSha256": paper_sha,
    "canonicalConfigSha256": config_sha,
    "runtimeConfigSha256": runtime_config_sha,
    "protocolClientManifestSha256": client_sha,
    "runnerScriptSha256": script_sha,
    "jvmArgumentsSha256": jvm_sha,
    "protocolTraceEnabled": trace_enabled == "1",
    "protocolTracePacketAllowlist": (
        sorted(trace_packet_allowlist.lower().split(","))
        if trace_packet_allowlist else None
    ),
    "protocolTraceAggregatePacketAllowlist": (
        sorted(trace_aggregate_packet_allowlist.lower().split(","))
        if trace_aggregate_packet_allowlist else None
    ),
    "traceWindowStartEpochMs": int(trace_window_start_epoch_ms),
    "traceWindowEndEpochMs": int(trace_window_end_epoch_ms),
    "availableCpuCount": int(available_cpu_count),
    "serverCpuSet": [int(value) for value in server_cpu_set.split(",")],
    "clientCpuSet": [int(value) for value in client_cpu_set.split(",")],
}, indent=2) + "\n", encoding="utf-8")
PY

send_console "ivcompare clear"
sleep 1

if grep -E "Could not pass event.*InteractionVisualizer|Exception.*InteractionVisualizer|InteractionVisualizer.*ERROR" \
    "$server_log" >/dev/null; then
  echo "InteractionVisualizer emitted a runtime error" >&2
  grep -E "Could not pass event.*InteractionVisualizer|Exception.*InteractionVisualizer|InteractionVisualizer.*ERROR" \
    "$server_log" >&2 || true
  exit 1
fi

assert_client_alive
kill -TERM "$client_pid"
if ! wait "$client_pid"; then
  echo "Protocol client did not stop cleanly" >&2
  tail -n 200 "$client_log" >&2 || true
  exit 1
fi
client_pid=""
python3 - "$client_state" <<'PY'
import json
import sys

state = json.load(open(sys.argv[1], encoding="utf-8"))
if state.get("phase") != "stopped":
    raise SystemExit(f"protocol client final phase is not stopped: {state.get('phase')!r}")
if state.get("keepAliveTimeoutMs") != 300000:
    raise SystemExit(
        f"protocol client keepalive timeout drifted: {state.get('keepAliveTimeoutMs')!r}"
    )
for field in ("loginSeen", "positionSeen", "chunkSeen", "playerLoadedSent"):
    if state.get(field) is not True:
        raise SystemExit(f"protocol client lost required ready state: {field}")
PY

if [[ "$protocol_trace_enabled" == 1 ]]; then
  test -s "$protocol_trace_path"
  node "$protocol_trace_analyzer_source" \
    --trace "$protocol_trace_path" \
    --window-start-epoch-ms "$trace_window_start_epoch_ms" \
    --window-end-epoch-ms "$trace_window_end_epoch_ms" \
    --output "$protocol_trace_analysis_path" \
    --overwrite > "$run_directory/protocol-trace-analysis.stdout.json"
  python3 - "$protocol_trace_analysis_path" "$scenario" "$variant" "$scene_size" \
    "$protocol_trace_packet_allowlist" \
    "$protocol_trace_aggregate_packet_allowlist" "$metrics_path" <<'PY'
import json
import sys

(
    analysis_path, scenario, variant, scene_size_text, packet_allowlist,
    aggregate_packet_allowlist, metrics_path,
) = sys.argv[1:]
analysis = json.load(open(analysis_path, encoding="utf-8"))
metrics = json.load(open(metrics_path, encoding="utf-8"))
expected_allowlist = (
    sorted(packet_allowlist.lower().split(",")) if packet_allowlist else None
)
if analysis.get("input", {}).get("capturePacketAllowlist") != expected_allowlist:
    raise SystemExit("protocol trace packet allowlist provenance mismatch")
expected_aggregate_allowlist = (
    sorted(aggregate_packet_allowlist.lower().split(","))
    if aggregate_packet_allowlist else None
)
if analysis.get("input", {}).get("aggregatePacketAllowlist") != expected_aggregate_allowlist:
    raise SystemExit("protocol trace aggregate packet allowlist provenance mismatch")
if analysis.get("status", {}).get("formalEvidenceReady") is not True:
    raise SystemExit("protocol trace is not complete formal evidence")
if analysis.get("traceCoverage", {}).get("windowEventCount", 0) <= 0:
    raise SystemExit("protocol trace window contains no visual lifecycle events")
spawn_observations = analysis.get("identity", {}).get("spawn", {}).get("observations", 0)
metadata_observations = analysis.get("counts", {}).get("byPacket", {}).get(
    "entity_metadata", 0
)
if expected_aggregate_allowlist and "entity_metadata" in expected_aggregate_allowlist:
    aggregated_metadata = analysis.get("counts", {}).get(
        "byPacketAggregated", {}
    ).get("entity_metadata", 0)
    window_aggregated = analysis.get("traceCoverage", {}).get(
        "windowAggregatedEventCount", -1
    )
    if aggregated_metadata != metadata_observations or window_aggregated != metadata_observations:
        raise SystemExit("aggregated metadata provenance/count mismatch")
scene_size = int(scene_size_text)
if scenario == "dropped-items" and spawn_observations < scene_size:
    raise SystemExit(
        f"dropped-item preflight saw {spawn_observations} spawns; "
        f"expected at least the {scene_size} real item entities"
    )
if scenario == "dropped-items" and variant == "B" and spawn_observations <= scene_size:
    raise SystemExit(
        f"rewritten dropped-item preflight saw {spawn_observations} spawns; "
        f"expected TextDisplay visuals beyond the {scene_size} real item entities"
    )
if scenario == "dropped-items" and metadata_observations < 5 * scene_size:
    raise SystemExit(
        f"dropped-item preflight saw only {metadata_observations} metadata packets; "
        f"expected at least {5 * scene_size} to prove repeated visual updates"
    )
minimum_block_spawns = 2 * (
    metrics.get("beehiveBlocks", 0) + metrics.get("beeNestBlocks", 0)
)
if scenario == "block-active" and spawn_observations < minimum_block_spawns:
    raise SystemExit(
        f"block-active preflight saw only {spawn_observations} visual spawns; "
        f"expected at least {minimum_block_spawns} for two TextDisplays per bee block"
    )
if metadata_observations <= 0:
    raise SystemExit("preflight observed no visual entity metadata")
PY
fi

send_console "stop"
for _ in $(seq 1 120); do
  kill -0 "$server_pid" 2>/dev/null || break
  sleep 1
done
if kill -0 "$server_pid" 2>/dev/null; then
  echo "Paper did not stop cleanly" >&2
  exit 1
fi
wait "$server_pid"
server_pid=""
exec 3>&-
console_open=0
rm -f -- "$console_fifo"
prune_runtime_payload
cleanup_complete=1
