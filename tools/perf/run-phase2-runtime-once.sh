#!/usr/bin/env bash
set -euo pipefail

# Runs one restart-isolated Paper + real TCP client sample. A workflow must
# invoke this script sequentially in the desired ABBA order; it deliberately
# owns only one JVM/run so cleanup failures cannot leak state into the next one.

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
protocol_client_source="$script_directory/phase2-protocol-client.js"
protocol_trace_analyzer_source="$script_directory/analyze-phase2-protocol-trace.js"

required_variable() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "$name is required" >&2
    exit 64
  fi
}

for name in \
  PHASE2_PLUGIN_JAR PHASE2_PAPER_JAR PHASE2_CLIENT_ROOT PHASE2_OUTPUT_ROOT \
  PHASE2_RUN_ID PHASE2_SCENARIO PHASE2_VARIANT; do
  required_variable "$name"
done

plugin_jar="$(realpath "$PHASE2_PLUGIN_JAR")"
paper_jar="$(realpath "$PHASE2_PAPER_JAR")"
client_root="$(realpath "$PHASE2_CLIENT_ROOT")"
output_root="$(realpath -m "$PHASE2_OUTPUT_ROOT")"
run_id="$PHASE2_RUN_ID"
scenario="$PHASE2_SCENARIO"
variant="$PHASE2_VARIANT"
server_port="${PHASE2_SERVER_PORT:-25566}"
item_count="${PHASE2_ITEM_COUNT:-4096}"
warmup_seconds="${PHASE2_WARMUP_SECONDS:-120}"
settle_seconds="${PHASE2_SETTLE_SECONDS:-20}"
measure_seconds="${PHASE2_MEASURE_SECONDS:-180}"
capture_enabled="${PHASE2_CAPTURE_ENABLED:-0}"
capture_snaplen="${PHASE2_CAPTURE_SNAPLEN:-128}"
protocol_trace_enabled="${PHASE2_PROTOCOL_TRACE_ENABLED:-$capture_enabled}"

[[ "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
  || { echo "PHASE2_RUN_ID contains unsafe characters" >&2; exit 64; }

case "$variant" in
  A|B) ;;
  *) echo "PHASE2_VARIANT must be A or B" >&2; exit 64 ;;
esac
case "$scenario" in
  static-steady|static-spawn|visibility-return|visibility-itemdisplay-return|visibility-textdisplay-return|block-idle|block-active|block-direct-write) ;;
  *) echo "Unsupported PHASE2_SCENARIO: $scenario" >&2; exit 64 ;;
esac
for value in "$server_port" "$item_count" "$warmup_seconds" "$settle_seconds" \
  "$measure_seconds" "$capture_snaplen"; do
  [[ "$value" =~ ^[0-9]+$ ]] || { echo "Numeric input is invalid: $value" >&2; exit 64; }
done
(( server_port >= 1 && server_port <= 65535 )) \
  || { echo "PHASE2_SERVER_PORT is outside 1..65535" >&2; exit 64; }
maximum_item_count=8192
if [[ "$scenario" == block-* ]]; then
  maximum_item_count=4096
fi
(( item_count >= 1 && item_count <= maximum_item_count )) \
  || { echo "PHASE2_ITEM_COUNT is outside 1..$maximum_item_count for $scenario" >&2; exit 64; }
(( warmup_seconds >= 10 && settle_seconds >= 5 && measure_seconds >= 5 )) \
  || { echo "Warmup/settle/measure windows are too short" >&2; exit 64; }
(( warmup_seconds + settle_seconds + measure_seconds + 120 <= 600 )) \
  || { echo "Requested window exceeds the benchmark runtime cap" >&2; exit 64; }
if [[ "$scenario" == visibility-* ]] && (( measure_seconds < 10 )); then
  echo "$scenario requires at least a 10-second fixed window" >&2
  exit 64
fi
if [[ "$scenario" == block-direct-write ]]; then
  direct_write_minimum_seconds=45
  if (( item_count > 1024 )); then
    direct_write_minimum_seconds=55
  fi
  if (( measure_seconds < direct_write_minimum_seconds )); then
    echo "block-direct-write with $item_count blocks requires at least $direct_write_minimum_seconds seconds for a complete safety-audit pass" >&2
    exit 64
  fi
fi
[[ "$capture_enabled" == 0 || "$capture_enabled" == 1 ]] \
  || { echo "PHASE2_CAPTURE_ENABLED must be 0 or 1" >&2; exit 64; }
[[ "$protocol_trace_enabled" == 0 || "$protocol_trace_enabled" == 1 ]] \
  || { echo "PHASE2_PROTOCOL_TRACE_ENABLED must be 0 or 1" >&2; exit 64; }
[[ -f "$plugin_jar" && -f "$paper_jar" ]] \
  || { echo "Plugin or Paper JAR is missing" >&2; exit 66; }
[[ -f "$client_root/client-build-manifest.json" ]] \
  || { echo "Prepared protocol client manifest is missing" >&2; exit 66; }
[[ -d "$client_root/node-minecraft-protocol" ]] \
  || { echo "Prepared node-minecraft-protocol directory is missing" >&2; exit 66; }
[[ -f "$protocol_client_source" ]] \
  || { echo "Protocol client source is missing" >&2; exit 66; }
if [[ "$protocol_trace_enabled" == 1 && ! -f "$protocol_trace_analyzer_source" ]]; then
  echo "Semantic protocol trace analyzer source is missing" >&2
  exit 66
fi

run_directory="$output_root/$run_id"
if [[ -e "$run_directory" ]]; then
  echo "Run output already exists: $run_directory" >&2
  exit 73
fi
mkdir -p "$run_directory/plugins/InteractionVisualizer" "$run_directory/plugins/bStats"
server_log="$run_directory/server.log"
client_log="$run_directory/protocol-client.log"
client_ready="$run_directory/protocol-client-ready.json"
client_state="$run_directory/protocol-client-state.json"
console_fifo="$run_directory/console.pipe"
capture_path="$run_directory/$run_id.pcap"
capture_log="$run_directory/tcpdump.log"
protocol_trace_path="$run_directory/$run_id.protocol-trace.json"
protocol_trace_analysis_path="$run_directory/$run_id.protocol-trace-analysis.json"
jvm_gc_safepoint_log_name="jvm-gc-safepoint.log"
jvm_gc_safepoint_log="$run_directory/$jvm_gc_safepoint_log_name"
jvm_diagnostics_metadata="$run_directory/jvm-diagnostics.json"
jvm_gc_safepoint_xlog="-Xlog:gc*=info,safepoint=info:file=$jvm_gc_safepoint_log_name:time,uptime,level,tags:filecount=0"
jvm_arguments_fingerprint="-Xms2G -Xmx2G -XX:+UseG1GC -XX:+AlwaysPreTouch -Dinteractionvisualizer.performance.allowBlockScene=true $jvm_gc_safepoint_xlog -Dfile.encoding=UTF-8"

cp "$paper_jar" "$run_directory/server.jar"
cp "$plugin_jar" "$run_directory/plugins/InteractionVisualizer.jar"
unzip -p "$plugin_jar" config.yml > "$run_directory/plugins/InteractionVisualizer/config.yml"

python3 - "$run_directory/plugins/InteractionVisualizer/config.yml" "$scenario" "$variant" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
scenario = sys.argv[2]
variant = sys.argv[3]
text = path.read_text(encoding="utf-8")

def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one config token {old!r}, found {count}")
    text = text.replace(old, new, 1)

packet_only = scenario == "visibility-return" or (
    scenario in {"static-steady", "static-spawn"} and variant == "B"
)
visibility_limit = scenario.startswith("visibility-") and variant == "B"
event_driven = scenario.startswith("block-") and variant == "B"
replace_once("      PacketOnlyStatic: false",
             f"      PacketOnlyStatic: {str(packet_only).lower()}")
replace_once("      Enabled: false\n      BucketSize: 128\n      RestorePerTick: 32",
             f"      Enabled: {str(visibility_limit).lower()}\n"
             "      BucketSize: 128\n      RestorePerTick: 32")
replace_once("      EventDriven: false",
             f"      EventDriven: {str(event_driven).lower()}")
replace_once("  Updater: true", "  Updater: false")
replace_once("  DownloadLanguageFiles: true", "  DownloadLanguageFiles: false")
path.write_text(text, encoding="utf-8", newline="\n")
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
level-name=phase2-world
level-seed=interactionvisualizer-phase2-fixed
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
max-players=1
max-tick-time=-1
motd=InteractionVisualizer Phase 2
network-compression-threshold=256
online-mode=false
server-ip=127.0.0.1
server-port=$server_port
simulation-distance=3
spawn-animals=false
spawn-monsters=false
spawn-npcs=false
spawn-protection=0
sync-chunk-writes=false
view-distance=3
white-list=false
EOF

plugin_sha="$(sha256sum "$plugin_jar" | awk '{print $1}')"
paper_sha="$(sha256sum "$paper_jar" | awk '{print $1}')"
client_manifest_sha="$(sha256sum "$client_root/client-build-manifest.json" | awk '{print $1}')"
config_sha="$(sha256sum "$run_directory/plugins/InteractionVisualizer/config.yml" | awk '{print $1}')"
protocol_client_sha="$(sha256sum "$protocol_client_source" | awk '{print $1}')"
protocol_trace_analyzer_sha=""
if [[ "$protocol_trace_enabled" == 1 ]]; then
  protocol_trace_analyzer_sha="$(sha256sum "$protocol_trace_analyzer_source" | awk '{print $1}')"
fi

server_pid=""
client_pid=""
capture_pid=""
console_open=0
cleanup_complete=0

prune_runtime_payload() {
  # Evidence lives in the root logs/state/JSON/pcap files. Paper's downloaded
  # runtime, remapped plugin copy and disposable worlds are reproducible inputs
  # or test state and otherwise inflate every artifact by hundreds of MB.
  rm -rf -- \
    "$run_directory/cache" \
    "$run_directory/libraries" \
    "$run_directory/logs" \
    "$run_directory/versions" \
    "$run_directory/phase2-world" \
    "$run_directory/phase2-world_nether" \
    "$run_directory/phase2-world_the_end" \
    "$run_directory/plugins/.paper-remapped"
  rm -f -- \
    "$run_directory/server.jar" \
    "$run_directory/plugins/InteractionVisualizer.jar"
}

stop_capture() {
  if [[ -n "$capture_pid" ]] && kill -0 "$capture_pid" 2>/dev/null; then
    sudo -n kill -INT "$capture_pid" 2>/dev/null || true
    wait "$capture_pid" 2>/dev/null || true
  fi
  capture_pid=""
}

cleanup() {
  local exit_status=$?
  if [[ "$cleanup_complete" == 1 ]]; then
    return "$exit_status"
  fi
  cleanup_complete=1
  set +e
  stop_capture
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

wait_for_log_count() {
  local pattern="$1"
  local minimum_count="$2"
  local timeout_seconds="$3"
  local observed
  for _ in $(seq 1 "$timeout_seconds"); do
    observed="$(grep -Fc -- "$pattern" "$server_log" 2>/dev/null || true)"
    if [[ "$observed" -ge "$minimum_count" ]]; then
      return 0
    fi
    if [[ -n "$server_pid" ]] && ! kill -0 "$server_pid" 2>/dev/null; then
      echo "Paper exited while waiting for occurrence $minimum_count of: $pattern" >&2
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for occurrence $minimum_count of: $pattern" >&2
  return 1
}

wait_for_file() {
  local file="$1"
  local timeout_seconds="$2"
  for _ in $(seq 1 "$timeout_seconds"); do
    [[ -s "$file" ]] && return 0
    if [[ -n "$client_pid" ]] && ! kill -0 "$client_pid" 2>/dev/null; then
      echo "Protocol client exited while waiting for $file" >&2
      tail -n 200 "$client_log" >&2 || true
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for $file" >&2
  tail -n 200 "$client_log" >&2 || true
  return 1
}

send_console() {
  printf '%s\n' "$1" >&3
}

capture_block_scene_record() {
  local expected_prefix="$1"
  local output_file="$2"
  local timeout_seconds="$3"
  local action_owner_prefix="${expected_prefix%% state=*}"
  local failure_line
  local line
  local record
  for _ in $(seq 1 "$timeout_seconds"); do
    if grep -Fq -- "$expected_prefix" "$server_log" 2>/dev/null; then
      break
    fi
    failure_line="$(grep -F -- "$action_owner_prefix state=" "$server_log" 2>/dev/null \
      | tail -n 1 || true)"
    if [[ -n "$failure_line" ]]; then
      echo "Block scene command returned an unexpected record: ${failure_line#*IV_BLOCK_SCENE }" >&2
      exit 1
    fi
    if [[ -n "$server_pid" ]] && ! kill -0 "$server_pid" 2>/dev/null; then
      echo "Paper exited while waiting for block scene record: $expected_prefix" >&2
      exit 1
    fi
    sleep 1
  done
  if ! grep -Fq -- "$expected_prefix" "$server_log" 2>/dev/null; then
    echo "Timed out waiting for block scene record: $expected_prefix" >&2
    tail -n 200 "$server_log" >&2 || true
    exit 1
  fi
  line="$(grep -F -- "$expected_prefix" "$server_log" | tail -n 1)"
  record="IV_BLOCK_SCENE ${line#*IV_BLOCK_SCENE }"
  if [[ "$record" == "IV_BLOCK_SCENE $line" || "$record" != "$expected_prefix"* ]]; then
    echo "Unable to extract exact block scene record: $expected_prefix" >&2
    exit 1
  fi
  printf '%s\n' "$record" > "$output_file"
}

validate_block_scene_record() {
  local record_file="$1"
  local phase="$2"
  local expected_mode="$3"
  local expected_count="$4"
  python3 - "$record_file" "$phase" "$expected_mode" "$expected_count" <<'PY'
from pathlib import Path
import sys

record_path, phase, expected_mode, expected_count_text = sys.argv[1:]
expected_count = int(expected_count_text)
record = Path(record_path).read_text(encoding="utf-8").strip()
parts = record.split()
if not parts or parts[0] != "IV_BLOCK_SCENE":
    raise SystemExit(f"invalid block scene record marker: {record!r}")

fields = {}
for token in parts[1:]:
    if "=" not in token:
        raise SystemExit(f"invalid block scene record token: {token!r}")
    key, value = token.split("=", 1)
    if key in fields:
        raise SystemExit(f"duplicate block scene record field: {key}")
    fields[key] = value

def require(name, expected):
    actual = fields.get(name)
    if actual != str(expected):
        raise SystemExit(f"{phase} record {name}={actual!r}, expected {expected!r}")

def integer(name):
    value = fields.get(name)
    try:
        return int(value)
    except (TypeError, ValueError):
        raise SystemExit(f"{phase} record {name} is not an integer: {value!r}")

require("action", phase)
require("owner", "IVBench")
require("mode", expected_mode)
require("requested", expected_count)
require("placed", expected_count)
require("skippedExternal", 0)
require("restoreFailures", 0)
require("unloaded", 0)
require("inspectionFailures", 0)

material_fields = ["furnace", "blastFurnace", "smoker", "beeHive", "beeNest"]
base, remainder = divmod(expected_count, len(material_fields))
expected_material_counts = {
    name: base + (1 if index < remainder else 0)
    for index, name in enumerate(material_fields)
}
for name, expected in expected_material_counts.items():
    require(name, expected)
require(
    "eventEligibleFurnaces",
    sum(expected_material_counts[name] for name in material_fields[:3]),
)
if integer("revision") < 1:
    raise SystemExit(f"{phase} record revision must be positive")

if phase == "create":
    require("state", "ready")
    require("owned", expected_count)
    require("remaining", expected_count)
    require("unresolved", 0)
    require("mutationRequested", 0)
    require("mutationApplied", 0)
    require("restored", 0)
    require("detail", "created")
elif phase == "mutate":
    require("state", "ready")
    require("mode", "direct_write")
    require("owned", expected_count)
    require("remaining", expected_count)
    require("unresolved", 0)
    require("mutationRequested", expected_count)
    require("mutationApplied", expected_count)
    require("restored", 0)
    require("detail", "eventless_direct_write")
elif phase == "clear":
    require("state", "cleared")
    require("owned", 0)
    require("remaining", 0)
    require("unresolved", 0)
    expected_mutations = expected_count if expected_mode == "direct_write" else 0
    require("mutationRequested", expected_mutations)
    require("mutationApplied", expected_mutations)
    require("restored", expected_count)
    require("detail", "cleared")
else:
    raise SystemExit(f"unknown block scene record phase: {phase}")
PY
}

mkfifo "$console_fifo"
exec 3<>"$console_fifo"
console_open=1
(
  cd "$run_directory"
  exec java -Xms2G -Xmx2G -XX:+UseG1GC -XX:+AlwaysPreTouch \
    -Dinteractionvisualizer.performance.allowBlockScene=true \
    "$jvm_gc_safepoint_xlog" \
    -Dfile.encoding=UTF-8 -jar server.jar --nogui < console.pipe
) > "$server_log" 2>&1 &
server_pid=$!

wait_for_log "[InteractionVisualizer] Enabled for Paper 26.1.2!" 240
wait_for_log "Done (" 240
if grep -Fq -- "No key layers in MapLike" "$server_log"; then
  echo "Paper rejected the flat-world generator settings" >&2
  exit 1
fi

send_console "difficulty peaceful"
send_console "gamerule minecraft:spawn_mobs false"
send_console "gamerule minecraft:advance_weather false"
send_console "gamerule minecraft:advance_time false"
send_console "gamerule minecraft:random_tick_speed 0"
send_console "gamerule minecraft:respawn_radius 0"
send_console "setworldspawn 0 -60 0"
send_console "weather clear"
send_console "time set day"
sleep 1
if grep -Fq -- "Incorrect argument for command" "$server_log"; then
  echo "Paper rejected one of the deterministic-world commands" >&2
  exit 1
fi

protocol_trace_environment=("PHASE2_PROTOCOL_TRACE_FILE=")
if [[ "$protocol_trace_enabled" == 1 ]]; then
  protocol_trace_environment=("PHASE2_PROTOCOL_TRACE_FILE=$protocol_trace_path")
fi
env \
  "PHASE2_MC_PROTOCOL_MODULE=$client_root/node-minecraft-protocol" \
  PHASE2_SERVER_HOST=127.0.0.1 \
  "PHASE2_SERVER_PORT=$server_port" \
  PHASE2_CLIENT_USERNAME=IVBench \
  PHASE2_CLIENT_VERSION=26.1.2 \
  "PHASE2_CLIENT_READY_FILE=$client_ready" \
  "PHASE2_CLIENT_STATE_FILE=$client_state" \
  "${protocol_trace_environment[@]}" \
  node "$protocol_client_source" > "$client_log" 2>&1 &
client_pid=$!
wait_for_file "$client_ready" 180
wait_for_log "IVBench joined the game" 60

send_console "iv perf clear IVBench"
wait_for_log "Cleared the benchmark scene for IVBench." 30

initial_coordinates="$(python3 - "$client_ready" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
p = data["position"]
print(f'{p["x"]:.6f} {p["y"]:.6f} {p["z"]:.6f}')
PY
)"
read -r initial_x initial_y initial_z <<< "$initial_coordinates"
lifetime_ticks=$(( (warmup_seconds + settle_seconds + measure_seconds + 120) * 20 ))
block_scene_enabled=0
block_scene_mode=""
block_scene_record_mode=""
block_scene_create_record="$run_directory/blockscene-create.record.txt"
block_scene_mutate_record="$run_directory/blockscene-mutate.record.txt"
block_scene_clear_record="$run_directory/blockscene-clear.record.txt"
block_scene_records_json="$run_directory/blockscene-records.json"
case "$scenario" in
  block-idle)
    block_scene_enabled=1
    block_scene_mode=idle
    block_scene_record_mode=idle
    ;;
  block-active)
    block_scene_enabled=1
    block_scene_mode=active
    block_scene_record_mode=active
    ;;
  block-direct-write)
    block_scene_enabled=1
    block_scene_mode=direct-write
    block_scene_record_mode=direct_write
    ;;
esac

if [[ "$block_scene_enabled" == 1 ]]; then
  block_create_prefix="IV_BLOCK_SCENE action=create owner=IVBench state=ready mode=$block_scene_record_mode requested=$item_count placed=$item_count owned=$item_count"
  send_console "iv perf blockscene create $block_scene_mode $item_count IVBench"
  capture_block_scene_record "$block_create_prefix" "$block_scene_create_record" 180
  validate_block_scene_record "$block_scene_create_record" create "$block_scene_record_mode" "$item_count"
else
  scene_type=static
  scene_entity_label=items
  case "$scenario" in
  visibility-itemdisplay-return)
    scene_type=itemdisplay
    scene_entity_label=entities
    ;;
  visibility-textdisplay-return)
    scene_type=textdisplay
    scene_entity_label=entities
    ;;
  esac
  scene_spawn_log="Spawned $item_count $scene_type benchmark $scene_entity_label"

  send_console "iv perf scene $scene_type $item_count $lifetime_ticks IVBench"
  wait_for_log "$scene_spawn_log" 60
fi
sleep "$warmup_seconds"

if [[ "$scenario" == static-spawn ]]; then
  clear_count="$(grep -Fc -- "Cleared the benchmark scene for IVBench." "$server_log" 2>/dev/null || true)"
  send_console "iv perf clear IVBench"
  wait_for_log_count "Cleared the benchmark scene for IVBench." "$((clear_count + 1))" 30
  sleep "$settle_seconds"
else
  # The steady target path, not only Paper/JVM startup, receives the full
  # warmup. The scene stays alive through the measured window.
  sleep "$settle_seconds"
fi

if [[ "$scenario" == visibility-* ]]; then
  far_x="$(python3 -c 'import sys; print(float(sys.argv[1]) + 512.0)' "$initial_x")"
  teleport_count="$(grep -Fc -- "Teleported IVBench" "$server_log" 2>/dev/null || true)"
  send_console "tp IVBench $far_x $initial_y $initial_z"
  wait_for_log_count "Teleported IVBench" "$((teleport_count + 1))" 30
  sleep 10
  unload_count="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["chunkUnloadCount"])' "$client_state")"
  [[ "$unload_count" -gt 0 ]] || { echo "No chunk unload was observed before visibility return" >&2; exit 1; }
fi

if [[ "$capture_enabled" == 1 ]]; then
  sudo -n tcpdump -i lo -s "$capture_snaplen" -B 4096 -U \
    -w "$capture_path" "tcp port $server_port" 2> "$capture_log" &
  capture_pid=$!
  sleep 2
  kill -0 "$capture_pid"
fi

send_console "iv perf start $run_id"
wait_for_log "Performance sampling started: $run_id" 30
window_start="$(python3 -c 'import time; print(f"{time.time():.6f}")')"
window_deadline="$(python3 -c 'import sys; print(f"{float(sys.argv[1]) + float(sys.argv[2]):.6f}")' \
  "$window_start" "$measure_seconds")"

if [[ "$scenario" == static-spawn ]]; then
  spawn_count="$(grep -Fc -- "$scene_spawn_log" "$server_log" 2>/dev/null || true)"
  send_console "iv perf scene static $item_count $lifetime_ticks IVBench"
  wait_for_log_count "$scene_spawn_log" "$((spawn_count + 1))" 60
elif [[ "$scenario" == visibility-* ]]; then
  teleport_count="$(grep -Fc -- "Teleported IVBench" "$server_log" 2>/dev/null || true)"
  send_console "tp IVBench $initial_x $initial_y $initial_z"
  wait_for_log_count "Teleported IVBench" "$((teleport_count + 1))" 30
elif [[ "$scenario" == block-direct-write ]]; then
  block_mutate_prefix="IV_BLOCK_SCENE action=mutate owner=IVBench state=ready mode=direct_write requested=$item_count placed=$item_count owned=$item_count"
  send_console "iv perf blockscene mutate $item_count direct-write IVBench"
  capture_block_scene_record "$block_mutate_prefix" "$block_scene_mutate_record" 120
  validate_block_scene_record "$block_scene_mutate_record" mutate direct_write "$item_count"
fi

python3 - "$window_deadline" <<'PY'
import sys
import time

deadline = float(sys.argv[1])
remaining = deadline - time.time()
if remaining < 0:
    raise SystemExit(f"benchmark action exceeded its fixed capture window by {-remaining:.3f}s")
time.sleep(remaining)
PY
window_end="$(python3 -c 'import time; print(f"{time.time():.6f}")')"
send_console "iv perf stop"
wait_for_log "IV_PERF {\"label\":\"$run_id\"" 60

if [[ "$capture_enabled" == 1 ]]; then
  stop_capture
  test -s "$capture_path"
  dropped_packets="$(sed -nE 's/^([0-9]+) packets dropped by kernel$/\1/p' "$capture_log" | tail -n 1)"
  [[ -n "$dropped_packets" ]] || { echo "tcpdump did not report kernel drop statistics" >&2; exit 1; }
  [[ "$dropped_packets" == 0 ]] || { echo "tcpdump dropped $dropped_packets packets" >&2; exit 1; }
  pwsh -NoProfile -File "$script_directory/analyze-phase2-pcap.ps1" "$capture_path" \
    -ServerPort "$server_port" \
    -WindowStartEpochSeconds "$window_start" \
    -WindowEndEpochSeconds "$window_end" \
    -OutputJson "$run_directory/$run_id.pcap-analysis.json" \
    -Overwrite > "$run_directory/pcap-analysis.stdout.json"
  python3 - "$run_directory/$run_id.pcap-analysis.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    analysis = json.load(stream)
if analysis.get("formalEvidenceReady") is not True:
    raise SystemExit("pcap analyzer did not produce a complete explicit-window result")
PY
fi

python3 - "$server_log" "$run_id" "$run_directory/iv-perf.json" "$scenario" "$variant" "$item_count" <<'PY'
import json
import math
import sys
log_path, run_id, output_path, scenario, variant, item_count_text = sys.argv[1:]
item_count = int(item_count_text)
matches = []
with open(log_path, encoding="utf-8", errors="replace") as stream:
    for line in stream:
        marker = line.find("IV_PERF ")
        if marker < 0:
            continue
        candidate = json.loads(line[marker + len("IV_PERF "):])
        if candidate.get("label") == run_id:
            matches.append(candidate)
if len(matches) != 1:
    raise SystemExit(f"Expected one IV_PERF record for {run_id}, found {len(matches)}")
metrics = matches[0]
if metrics.get("droppedTickSamples") != 0:
    raise SystemExit(f"droppedTickSamples={metrics.get('droppedTickSamples')}")
if metrics.get("tickSamples", 0) <= 0 or metrics.get("seconds", 0) <= 0:
    raise SystemExit("IV_PERF sampling window is empty")
slowest_tick = metrics.get("msptMaxBukkitTick")
slowest_epoch_ms = metrics.get("msptMaxEndEpochMillis")
slowest_block_checks = metrics.get("msptMaxBlockUpdateChecks")
slowest_block_ms = metrics.get("msptMaxBlockUpdateMs")
total_block_checks = metrics.get("blockUpdateChecks")
total_block_ms = metrics.get("blockUpdateMs")
if isinstance(slowest_tick, bool) or not isinstance(slowest_tick, int) or slowest_tick < 0:
    raise SystemExit(f"invalid msptMaxBukkitTick={slowest_tick!r}")
if isinstance(slowest_epoch_ms, bool) or not isinstance(slowest_epoch_ms, int) or slowest_epoch_ms <= 0:
    raise SystemExit(f"invalid msptMaxEndEpochMillis={slowest_epoch_ms!r}")
if (isinstance(slowest_block_checks, bool) or not isinstance(slowest_block_checks, int)
        or slowest_block_checks < 0):
    raise SystemExit(f"invalid msptMaxBlockUpdateChecks={slowest_block_checks!r}")
if (isinstance(slowest_block_ms, bool) or not isinstance(slowest_block_ms, (int, float))
        or not math.isfinite(slowest_block_ms) or slowest_block_ms < 0):
    raise SystemExit(f"invalid msptMaxBlockUpdateMs={slowest_block_ms!r}")
if (isinstance(total_block_checks, bool) or not isinstance(total_block_checks, int)
        or total_block_checks < 0):
    raise SystemExit(f"invalid blockUpdateChecks={total_block_checks!r}")
if (isinstance(total_block_ms, bool) or not isinstance(total_block_ms, (int, float))
        or not math.isfinite(total_block_ms) or total_block_ms < 0):
    raise SystemExit(f"invalid blockUpdateMs={total_block_ms!r}")
if slowest_block_checks > total_block_checks or slowest_block_ms > total_block_ms:
    raise SystemExit(
        "slowest-tick block attribution exceeds the complete sample: "
        f"checks={slowest_block_checks}/{total_block_checks} "
        f"ms={slowest_block_ms}/{total_block_ms}"
    )
visibility_scenario = scenario.startswith("visibility-")
block_scenario = scenario.startswith("block-")
expected_packet_only = scenario == "visibility-return" or (
    scenario in {"static-steady", "static-spawn"} and variant == "B"
)
expected_limiter = visibility_scenario and variant == "B"
expected_event_driven = block_scenario and variant == "B"
if metrics.get("packetOnlyStatic") is not expected_packet_only:
    raise SystemExit(f"packetOnlyStatic does not match {scenario}/{variant}")
if metrics.get("visibilityRateLimit") is not expected_limiter:
    raise SystemExit(f"visibilityRateLimit does not match {scenario}/{variant}")
if metrics.get("eventDrivenBlockUpdates") is not expected_event_driven:
    raise SystemExit(f"eventDrivenBlockUpdates does not match {scenario}/{variant}")
if block_scenario:
    block_checks = metrics.get("blockUpdateChecks")
    block_ms = metrics.get("blockUpdateMs")
    if isinstance(block_checks, bool) or not isinstance(block_checks, int) or block_checks < 0:
        raise SystemExit(f"invalid blockUpdateChecks={block_checks!r}")
    if (isinstance(block_ms, bool) or not isinstance(block_ms, (int, float))
            or not math.isfinite(block_ms) or block_ms < 0):
        raise SystemExit(f"invalid blockUpdateMs={block_ms!r}")
    if variant == "A" and block_checks <= 0:
        raise SystemExit("legacy block updater performed no checks")
    if scenario == "block-active" and variant == "B" and block_checks <= 0:
        raise SystemExit("event-driven active block workload performed no checks")
    if scenario == "block-direct-write" and variant == "B" and block_checks < item_count:
        raise SystemExit(
            f"direct-write safety audit checked only {block_checks} of {item_count} applied mutations"
        )
    if block_checks > 0 and block_ms <= 0:
        raise SystemExit(f"block updater recorded {block_checks} checks but no elapsed time")
if scenario == "static-spawn":
    if variant == "B":
        if metrics.get("packetOnlyItemSyncs") != item_count or metrics.get("bukkitEntitySpawns") != 0:
            raise SystemExit("candidate did not exercise the pure packet-only spawn path")
    elif metrics.get("packetOnlyItemSyncs") != 0 or metrics.get("bukkitEntitySpawns") != item_count:
        raise SystemExit("baseline did not exercise one Bukkit tracker anchor per logical item")
if visibility_scenario:
    queued = metrics.get("visibilityShowsQueued")
    drained = metrics.get("visibilityShowsDrained")
    if variant == "B" and (queued != item_count or drained != item_count):
        raise SystemExit(f"limiter did not queue/drain every entity: queued={queued} drained={drained}")
    if variant == "A" and (queued != 0 or drained != 0):
        raise SystemExit(f"unlimited baseline unexpectedly used the visibility queue: queued={queued} drained={drained}")
if scenario == "visibility-return":
    if metrics.get("virtualSpawnBundles") != item_count:
        raise SystemExit("visibility return did not respawn every virtual item within the window")
elif scenario in {"visibility-itemdisplay-return", "visibility-textdisplay-return"}:
    if metrics.get("packetOnlyItemSyncs") != 0 or metrics.get("virtualSpawnBundles") != 0:
        raise SystemExit("generic display visibility scenario leaked into the virtual-item branch")
    if metrics.get("bukkitShowCalls") != item_count:
        raise SystemExit(f"generic display visibility restored {metrics.get('bukkitShowCalls')} of {item_count} entities")
with open(output_path, "w", encoding="utf-8", newline="\n") as stream:
    json.dump(metrics, stream, ensure_ascii=False, indent=2)
    stream.write("\n")
PY

block_scene_manifest_enabled=false
block_scene_manifest_mode=null
block_scene_manifest_records_path=null
block_scene_manifest_records_sha=null
if [[ "$block_scene_enabled" == 1 ]]; then
  block_clear_prefix="IV_BLOCK_SCENE action=clear owner=IVBench state=cleared mode=$block_scene_record_mode requested=$item_count placed=$item_count owned=0"
  send_console "iv perf blockscene clear IVBench"
  capture_block_scene_record "$block_clear_prefix" "$block_scene_clear_record" 180
  validate_block_scene_record "$block_scene_clear_record" clear "$block_scene_record_mode" "$item_count"
  python3 - "$scenario" "$block_scene_create_record" "$block_scene_mutate_record" \
    "$block_scene_clear_record" "$block_scene_records_json" \
    "$run_directory/iv-perf.json" <<'PY'
from pathlib import Path
import json
import math
import sys

scenario, create_path_text, mutate_path_text, clear_path_text, output_path_text, metrics_path_text = sys.argv[1:]

def parse_record(path_text):
    path = Path(path_text)
    raw = path.read_text(encoding="utf-8").strip()
    fields = {}
    for token in raw.split()[1:]:
        key, value = token.split("=", 1)
        fields[key] = value
    return {"path": path.name, "raw": raw, "fields": fields}

create = parse_record(create_path_text)
clear = parse_record(clear_path_text)
mutate_path = Path(mutate_path_text)
mutate = parse_record(mutate_path_text) if mutate_path.is_file() else None
with Path(metrics_path_text).open(encoding="utf-8") as stream:
    metrics = json.load(stream)
create_revision = int(create["fields"]["revision"])
clear_revision = int(clear["fields"]["revision"])
direct_write_diagnostics = None
if scenario == "block-direct-write":
    if mutate is None:
        raise SystemExit("direct-write block scene is missing its mutate machine record")
    mutate_revision = int(mutate["fields"]["revision"])
    if mutate_revision != create_revision + 1 or clear_revision != mutate_revision:
        raise SystemExit(
            f"unexpected direct-write revisions: create={create_revision} "
            f"mutate={mutate_revision} clear={clear_revision}"
        )
    mutation_start_tick = int(mutate["fields"]["mutationStartBukkitTick"])
    mutation_end_tick = int(mutate["fields"]["mutationEndBukkitTick"])
    mutation_elapsed_ms = float(mutate["fields"]["mutationElapsedMs"])
    if mutation_start_tick < 0 or mutation_end_tick != mutation_start_tick:
        raise SystemExit(
            f"direct-write mutation did not complete synchronously in one Bukkit tick: "
            f"start={mutation_start_tick} end={mutation_end_tick}"
        )
    if not math.isfinite(mutation_elapsed_ms) or mutation_elapsed_ms <= 0:
        raise SystemExit(f"invalid direct-write mutationElapsedMs={mutation_elapsed_ms!r}")
    for field in ("mutationStartBukkitTick", "mutationEndBukkitTick", "mutationElapsedMs"):
        if clear["fields"].get(field) != mutate["fields"].get(field):
            raise SystemExit(f"clear record did not preserve direct-write timing field {field}")
    slowest_tick = int(metrics["msptMaxBukkitTick"])
    slowest_epoch_ms = int(metrics["msptMaxEndEpochMillis"])
    slowest_block_checks = int(metrics["msptMaxBlockUpdateChecks"])
    slowest_block_ms = float(metrics["msptMaxBlockUpdateMs"])
    slowest_ms = float(metrics["msptMax"])
    direct_write_diagnostics = {
        "schemaVersion": 1,
        "mutationStartBukkitTick": mutation_start_tick,
        "mutationEndBukkitTick": mutation_end_tick,
        "mutationElapsedMs": mutation_elapsed_ms,
        "slowestBukkitTick": slowest_tick,
        "slowestTickEndEpochMillis": slowest_epoch_ms,
        "slowestTickMs": slowest_ms,
        "slowestTickBlockUpdateChecks": slowest_block_checks,
        "slowestTickBlockUpdateMs": slowest_block_ms,
        "slowestTickWithinMutation": mutation_start_tick <= slowest_tick <= mutation_end_tick,
        "mutationFractionOfSlowestTick": (
            mutation_elapsed_ms / slowest_ms if slowest_ms > 0 else None
        ),
        "blockUpdateFractionOfSlowestTick": (
            slowest_block_ms / slowest_ms if slowest_ms > 0 else None
        ),
    }
elif mutate is not None:
    raise SystemExit(f"{scenario} unexpectedly produced a mutate machine record")
elif clear_revision != create_revision:
    raise SystemExit(
        f"unexpected block scene revisions: create={create_revision} clear={clear_revision}"
    )

evidence = {
    "schemaVersion": 2,
    "scenario": scenario,
    "formalEvidenceReady": True,
    "records": {"create": create, "mutate": mutate, "clear": clear},
    "directWriteDiagnostics": direct_write_diagnostics,
}
Path(output_path_text).write_text(
    json.dumps(evidence, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY
  block_scene_records_sha="$(sha256sum "$block_scene_records_json" | awk '{print $1}')"
  block_scene_manifest_enabled=true
  block_scene_manifest_mode="\"$block_scene_record_mode\""
  block_scene_manifest_records_path="\"blockscene-records.json\""
  block_scene_manifest_records_sha="\"$block_scene_records_sha\""
else
  send_console "iv perf clear IVBench"
fi
sleep 2
if ! kill -0 "$client_pid" 2>/dev/null; then
  echo "Protocol client exited before semantic trace finalization" >&2
  tail -n 200 "$client_log" >&2 || true
  wait "$client_pid" 2>/dev/null || true
  exit 1
fi
kill -TERM "$client_pid"
wait "$client_pid"
client_pid=""

protocol_trace_manifest_enabled=false
protocol_trace_manifest_path=null
protocol_trace_manifest_analysis_path=null
protocol_trace_manifest_formal_ready=null
protocol_trace_manifest_trace_sha=null
protocol_trace_manifest_analysis_sha=null
protocol_trace_manifest_analyzer_sha=null
if [[ "$protocol_trace_enabled" == 1 ]]; then
  test -s "$protocol_trace_path"
  node "$protocol_trace_analyzer_source" \
    --trace "$protocol_trace_path" \
    --window-start-epoch-seconds "$window_start" \
    --window-end-epoch-seconds "$window_end" \
    --output "$protocol_trace_analysis_path" \
    --overwrite > "$run_directory/protocol-trace-analysis.stdout.json"
  python3 - "$protocol_trace_analysis_path" "$scenario" "$item_count" <<'PY'
import json
import sys

analysis_path, scenario, item_count_text = sys.argv[1:]
with open(analysis_path, encoding="utf-8") as stream:
    analysis = json.load(stream)
status = analysis.get("status", {})
if status.get("formalEvidenceReady") is not True:
    raise SystemExit("protocol trace analyzer did not produce complete explicit-window evidence")
if analysis.get("traceCoverage", {}).get("windowEventCount", 0) <= 0:
    raise SystemExit("protocol trace explicit window contains no lifecycle events")
spawn_observations = analysis.get("identity", {}).get("spawn", {}).get("observations", 0)
entity_lifecycle_scenarios = {
    "static-spawn",
    "visibility-return",
    "visibility-itemdisplay-return",
    "visibility-textdisplay-return",
}
if scenario in entity_lifecycle_scenarios and spawn_observations < int(item_count_text):
    raise SystemExit(
        f"protocol trace observed only {spawn_observations} spawn identities; "
        f"expected at least {item_count_text}"
    )
PY
  protocol_trace_sha="$(sha256sum "$protocol_trace_path" | awk '{print $1}')"
  protocol_trace_analysis_sha="$(sha256sum "$protocol_trace_analysis_path" | awk '{print $1}')"
  protocol_trace_manifest_enabled=true
  protocol_trace_manifest_path="\"$run_id.protocol-trace.json\""
  protocol_trace_manifest_analysis_path="\"$run_id.protocol-trace-analysis.json\""
  protocol_trace_manifest_formal_ready=true
  protocol_trace_manifest_trace_sha="\"$protocol_trace_sha\""
  protocol_trace_manifest_analysis_sha="\"$protocol_trace_analysis_sha\""
  protocol_trace_manifest_analyzer_sha="\"$protocol_trace_analyzer_sha\""
fi

send_console "stop"
for _ in $(seq 1 90); do
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

if grep -Eq '^\[[^]]+ ERROR\]:' "$server_log"; then
  echo "Paper logged an ERROR during $run_id" >&2
  grep -E '^\[[^]]+ ERROR\]:' "$server_log" >&2 || true
  exit 1
fi

python3 - \
  "$jvm_gc_safepoint_log" \
  "$jvm_diagnostics_metadata" \
  "$jvm_gc_safepoint_log_name" \
  "$jvm_arguments_fingerprint" \
  "$jvm_gc_safepoint_xlog" <<'PY'
from pathlib import Path
import hashlib
import json
import re
import sys

log_path = Path(sys.argv[1])
metadata_path = Path(sys.argv[2])
log_name = sys.argv[3]
jvm_arguments_fingerprint = sys.argv[4]
unified_logging_option = sys.argv[5]

if not log_path.is_file():
    raise SystemExit(f"finalized JVM diagnostic log is missing: {log_path}")
raw = log_path.read_bytes()
if not raw:
    raise SystemExit(f"finalized JVM diagnostic log is empty: {log_path}")
if b"\x00" in raw:
    raise SystemExit(f"finalized JVM diagnostic log contains NUL bytes: {log_path}")
try:
    text = raw.decode("utf-8")
except UnicodeDecodeError as error:
    raise SystemExit(f"finalized JVM diagnostic log is not UTF-8: {error}") from error

lines = [line for line in text.splitlines() if line.strip()]
if not lines:
    raise SystemExit(f"finalized JVM diagnostic log has no records: {log_path}")

record_pattern = re.compile(
    r"^\[[^\]\r\n]+\]"
    r"\[(?:\d+(?:\.\d+)?|\.\d+)s\]"
    r"\[(trace|debug|info|warning|error)\]"
    r"\[([^\]\r\n]+)\](?: .*)?$"
)
malformed = []
gc_tagged_line_count = 0
safepoint_tagged_line_count = 0
gc_pause_line_count = 0
safepoint_event_line_count = 0
for line_number, line in enumerate(lines, start=1):
    match = record_pattern.fullmatch(line)
    if match is None:
        malformed.append((line_number, line[:160]))
        continue
    tags = {tag.strip() for tag in match.group(2).split(",")}
    message = line[match.end(2) + 1 :].lstrip()
    if "gc" in tags:
        gc_tagged_line_count += 1
        if "Pause" in message:
            gc_pause_line_count += 1
    if "safepoint" in tags:
        safepoint_tagged_line_count += 1
        if "Safepoint " in message:
            safepoint_event_line_count += 1

if malformed:
    preview = "; ".join(f"line {number}: {line!r}" for number, line in malformed[:5])
    raise SystemExit(f"malformed JVM unified-log records: {preview}")
if gc_tagged_line_count <= 0:
    raise SystemExit("finalized JVM diagnostic log contains no GC-tagged records")
if safepoint_tagged_line_count <= 0:
    raise SystemExit("finalized JVM diagnostic log contains no safepoint-tagged records")

metadata = {
    "schemaVersion": 1,
    "formalEvidenceReady": True,
    "finalizedAfterServerExit": True,
    "serverStoppedCleanly": True,
    "jvmArgumentsFingerprint": jvm_arguments_fingerprint,
    "unifiedLoggingOption": unified_logging_option,
    "gcSafepointLog": {
        "path": log_name,
        "sha256": hashlib.sha256(raw).hexdigest(),
        "sizeBytes": len(raw),
        "lineCount": len(lines),
        "gcTaggedLineCount": gc_tagged_line_count,
        "safepointTaggedLineCount": safepoint_tagged_line_count,
        "gcPauseLineCount": gc_pause_line_count,
        "safepointEventLineCount": safepoint_event_line_count,
        "rotationDisabled": True,
        "decorations": ["time", "uptime", "level", "tags"],
    },
}
metadata_path.write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY

test -s "$jvm_diagnostics_metadata"
jvm_gc_safepoint_sha="$(sha256sum "$jvm_gc_safepoint_log" | awk '{print $1}')"
python3 - \
  "$jvm_diagnostics_metadata" \
  "$jvm_gc_safepoint_sha" \
  "$jvm_arguments_fingerprint" \
  "$jvm_gc_safepoint_xlog" <<'PY'
import json
import sys

metadata_path, expected_log_sha, expected_arguments, expected_logging_option = sys.argv[1:]
with open(metadata_path, encoding="utf-8") as stream:
    metadata = json.load(stream)
if metadata.get("formalEvidenceReady") is not True:
    raise SystemExit("JVM diagnostic metadata is not formal-evidence ready")
if metadata.get("finalizedAfterServerExit") is not True:
    raise SystemExit("JVM diagnostic metadata was not finalized after server exit")
if metadata.get("jvmArgumentsFingerprint") != expected_arguments:
    raise SystemExit("JVM argument fingerprint mismatch in diagnostic metadata")
if metadata.get("unifiedLoggingOption") != expected_logging_option:
    raise SystemExit("JVM unified-logging option mismatch in diagnostic metadata")
recorded_log_sha = metadata.get("gcSafepointLog", {}).get("sha256")
if recorded_log_sha != expected_log_sha:
    raise SystemExit(
        f"finalized JVM diagnostic SHA mismatch: metadata={recorded_log_sha!r} "
        f"actual={expected_log_sha!r}"
    )
PY
jvm_diagnostics_metadata_sha="$(sha256sum "$jvm_diagnostics_metadata" | awk '{print $1}')"
jvm_diagnostics_manifest_json="$(python3 - \
  "$jvm_diagnostics_metadata" \
  "$jvm_diagnostics_metadata_sha" <<'PY'
import json
import sys

metadata_path, metadata_sha = sys.argv[1:]
with open(metadata_path, encoding="utf-8") as stream:
    metadata = json.load(stream)
metadata["metadataPath"] = "jvm-diagnostics.json"
metadata["metadataSha256"] = metadata_sha
print(json.dumps(metadata, ensure_ascii=False, separators=(",", ":")))
PY
)"

cat > "$run_directory/run-manifest.json" <<EOF
{
  "schemaVersion": 3,
  "runId": "$run_id",
  "scenario": "$scenario",
  "variant": "$variant",
  "blockSceneMutationOptIn": true,
  "captureEnabled": $([[ "$capture_enabled" == 1 ]] && echo true || echo false),
  "captureSnaplen": $capture_snaplen,
  "serverPort": $server_port,
  "itemCount": $item_count,
  "workloadCount": $item_count,
  "warmupSeconds": $warmup_seconds,
  "settleSeconds": $settle_seconds,
  "measureSeconds": $measure_seconds,
  "windowStartEpochSeconds": $window_start,
  "windowEndEpochSeconds": $window_end,
  "pluginSha256": "$plugin_sha",
  "paperSha256": "$paper_sha",
  "clientManifestSha256": "$client_manifest_sha",
  "configSha256": "$config_sha",
  "protocolTrace": {
    "enabled": $protocol_trace_manifest_enabled,
    "path": $protocol_trace_manifest_path,
    "analysisPath": $protocol_trace_manifest_analysis_path,
    "formalEvidenceReady": $protocol_trace_manifest_formal_ready,
    "traceSha256": $protocol_trace_manifest_trace_sha,
    "analysisSha256": $protocol_trace_manifest_analysis_sha,
    "protocolClientSha256": "$protocol_client_sha",
    "analyzerSha256": $protocol_trace_manifest_analyzer_sha
  },
  "blockScene": {
    "enabled": $block_scene_manifest_enabled,
    "mode": $block_scene_manifest_mode,
    "recordsPath": $block_scene_manifest_records_path,
    "recordsSha256": $block_scene_manifest_records_sha
  },
  "jvmDiagnostics": $jvm_diagnostics_manifest_json
}
EOF

python3 - \
  "$run_directory/run-manifest.json" \
  "$jvm_diagnostics_metadata" \
  "$jvm_diagnostics_metadata_sha" \
  "$jvm_gc_safepoint_sha" <<'PY'
from pathlib import Path
import hashlib
import json
import sys

manifest_path = Path(sys.argv[1])
metadata_path = Path(sys.argv[2])
expected_metadata_sha = sys.argv[3]
expected_log_sha = sys.argv[4]
with manifest_path.open(encoding="utf-8") as stream:
    manifest = json.load(stream)
with metadata_path.open(encoding="utf-8") as stream:
    metadata = json.load(stream)

actual_metadata_sha = hashlib.sha256(metadata_path.read_bytes()).hexdigest()
if actual_metadata_sha != expected_metadata_sha:
    raise SystemExit("JVM diagnostic metadata changed before manifest validation")
embedded = manifest.get("jvmDiagnostics")
if not isinstance(embedded, dict):
    raise SystemExit("run manifest has no JVM diagnostic metadata")
if embedded.get("metadataPath") != "jvm-diagnostics.json":
    raise SystemExit("run manifest has the wrong JVM diagnostic metadata path")
if embedded.get("metadataSha256") != expected_metadata_sha:
    raise SystemExit("run manifest has the wrong JVM diagnostic metadata SHA")
embedded_metadata = dict(embedded)
embedded_metadata.pop("metadataPath")
embedded_metadata.pop("metadataSha256")
if embedded_metadata != metadata:
    raise SystemExit("run manifest JVM diagnostics do not match jvm-diagnostics.json")
if embedded.get("gcSafepointLog", {}).get("sha256") != expected_log_sha:
    raise SystemExit("run manifest has the wrong finalized JVM diagnostic log SHA")
PY

echo "Completed Phase 2 runtime sample $run_id"
