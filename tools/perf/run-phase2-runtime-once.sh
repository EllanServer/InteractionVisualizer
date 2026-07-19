#!/usr/bin/env bash
set -euo pipefail

# Runs one restart-isolated Paper + real TCP client sample. A workflow must
# invoke this script sequentially in the desired ABBA order; it deliberately
# owns only one JVM/run so cleanup failures cannot leak state into the next one.

plugin_enabled_log_marker() {
  case "${1:-}" in
    26.1.2)
      printf '[InteractionVisualizer] Enabled for Paper %s!' "$1"
      ;;
    *)
      return 64
      ;;
  esac
}

if [[ "${1:-}" == --self-test ]]; then
  [[ "$(plugin_enabled_log_marker 26.1.2)" == \
      '[InteractionVisualizer] Enabled for Paper 26.1.2!' ]]
  if plugin_enabled_log_marker 26.2 >/dev/null 2>&1; then
    echo 'runtime harness accepted a non-canonical Paper version' >&2
    exit 1
  fi
  printf '{"passed":true,"canonicalPaperVersion":"26.1.2","paperEnableMarkers":["26.1.2"]}\n'
  exit 0
fi

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
paper_version="${PHASE2_PAPER_VERSION:-26.1.2}"
paper_channel="${PHASE2_PAPER_CHANNEL:-UNKNOWN}"
paper_build_id="${PHASE2_PAPER_BUILD_ID:-0}"
client_root="$(realpath "$PHASE2_CLIENT_ROOT")"
output_root="$(realpath -m "$PHASE2_OUTPUT_ROOT")"
run_id="$PHASE2_RUN_ID"
scenario="$PHASE2_SCENARIO"
variant="$PHASE2_VARIANT"
server_port="${PHASE2_SERVER_PORT:-25566}"
item_count="${PHASE2_ITEM_COUNT:-4096}"
dropped_nearby_item_count="${PHASE2_DROPPED_NEARBY_ITEM_COUNT:-128}"
warmup_seconds="${PHASE2_WARMUP_SECONDS:-120}"
settle_seconds="${PHASE2_SETTLE_SECONDS:-20}"
measure_seconds="${PHASE2_MEASURE_SECONDS:-180}"
capture_enabled="${PHASE2_CAPTURE_ENABLED:-0}"
capture_snaplen="${PHASE2_CAPTURE_SNAPLEN:-128}"
protocol_trace_enabled="${PHASE2_PROTOCOL_TRACE_ENABLED:-$capture_enabled}"
protocol_trace_max_events="${PHASE2_PROTOCOL_TRACE_MAX_EVENTS:-500000}"
protocol_trace_packet_allowlist="${PHASE2_PROTOCOL_TRACE_PACKET_ALLOWLIST-bundle_delimiter,entity_destroy,spawn_entity}"
protocol_trace_aggregate_packet_allowlist="${PHASE2_PROTOCOL_TRACE_AGGREGATE_PACKET_ALLOWLIST-entity_metadata}"
spark_profile_mode="${PHASE2_SPARK_PROFILE_MODE:-none}"
ab_factor="${PHASE2_AB_FACTOR:-scenario-config}"

[[ "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
  || { echo "PHASE2_RUN_ID contains unsafe characters" >&2; exit 64; }

case "$variant" in
  A|B) ;;
  *) echo "PHASE2_VARIANT must be A or B" >&2; exit 64 ;;
esac
case "$paper_version" in
  26.1.2) ;;
  *) echo "PHASE2_PAPER_VERSION must be the canonical benchmark version 26.1.2" >&2; exit 64 ;;
esac
case "$paper_channel" in
  STABLE|BETA|UNKNOWN) ;;
  *) echo "PHASE2_PAPER_CHANNEL must be STABLE, BETA, or UNKNOWN" >&2; exit 64 ;;
esac
[[ "$paper_build_id" =~ ^[0-9]+$ ]] \
  || { echo "PHASE2_PAPER_BUILD_ID must be numeric" >&2; exit 64; }
case "$scenario" in
  static-steady|static-spawn|visibility-return|visibility-itemdisplay-return|visibility-textdisplay-return|dropped-items|block-idle|block-active|block-direct-write) ;;
  *) echo "Unsupported PHASE2_SCENARIO: $scenario" >&2; exit 64 ;;
esac
case "$ab_factor" in
  scenario-config|legacy-text-component-cache|dropped-item-section-candidates) ;;
  *) echo "PHASE2_AB_FACTOR must be scenario-config, legacy-text-component-cache, or dropped-item-section-candidates" >&2; exit 64 ;;
esac
if [[ "$ab_factor" == legacy-text-component-cache && "$scenario" != block-active ]]; then
  echo "legacy-text-component-cache A/B is isolated to block-active" >&2
  exit 64
fi
for value in "$server_port" "$item_count" "$dropped_nearby_item_count" \
  "$warmup_seconds" "$settle_seconds" \
  "$measure_seconds" "$capture_snaplen" "$protocol_trace_max_events"; do
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
if [[ "$scenario" == dropped-items ]] && \
    (( dropped_nearby_item_count < 1 || dropped_nearby_item_count > item_count )); then
  echo "PHASE2_DROPPED_NEARBY_ITEM_COUNT is outside 1..$item_count" >&2
  exit 64
fi
if [[ "$ab_factor" == legacy-text-component-cache ]] && (( item_count < 100 )); then
  echo "legacy-text-component-cache A/B requires at least 100 workload blocks" >&2
  exit 64
fi
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
(( protocol_trace_max_events >= 1 )) \
  || { echo "PHASE2_PROTOCOL_TRACE_MAX_EVENTS must be positive" >&2; exit 64; }
for allowlist in "$protocol_trace_packet_allowlist" "$protocol_trace_aggregate_packet_allowlist"; do
  [[ -z "$allowlist" || "$allowlist" =~ ^[A-Za-z0-9_-]+(,[A-Za-z0-9_-]+)*$ ]] \
    || { echo "Protocol trace allowlist is invalid: $allowlist" >&2; exit 64; }
done
case "$spark_profile_mode" in
  none|cpu|cpu-all|alloc) ;;
  *) echo "PHASE2_SPARK_PROFILE_MODE must be none, cpu, cpu-all, or alloc" >&2; exit 64 ;;
esac
if [[ "$spark_profile_mode" == cpu-all && \
      "$scenario" != block-active && "$scenario" != block-direct-write && \
      "$scenario" != dropped-items ]]; then
  echo "Spark cpu-all profiling is isolated to block-active, block-direct-write, or dropped-items" >&2
  exit 64
fi
if [[ "$spark_profile_mode" != none && "$spark_profile_mode" != cpu-all && \
      "$scenario" != block-direct-write && "$scenario" != dropped-items ]]; then
  echo "Spark cpu/alloc profiling is isolated to block-direct-write or dropped-items" >&2
  exit 64
fi
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
jvm_command_line_metadata="$run_directory/jvm-command-line.json"
jvm_diagnostics_metadata="$run_directory/jvm-diagnostics.json"
spark_profile_output="$run_directory/$run_id.spark-$spark_profile_mode.sparkprofile"
spark_profile_metadata="$run_directory/spark-profile.json"
jvm_gc_safepoint_xlog="-Xlog:gc*=info,safepoint=info:file=$jvm_gc_safepoint_log_name:time,uptime,level,tags:filecount=0"
legacy_text_cache_disable_property=false
if [[ "$ab_factor" == legacy-text-component-cache && "$variant" == A ]]; then
  legacy_text_cache_disable_property=true
fi
legacy_text_cache_enabled=true
if [[ "$legacy_text_cache_disable_property" == true ]]; then
  legacy_text_cache_enabled=false
fi
dropped_source_owned_candidates=false
if [[ "$ab_factor" == dropped-item-section-candidates && "$variant" == B ]]; then
  dropped_source_owned_candidates=true
fi
legacy_text_cache_jvm_argument="-Dinteractionvisualizer.disableLegacyTextComponentCache=$legacy_text_cache_disable_property"
legacy_text_cache_jvm_argument_template="-Dinteractionvisualizer.disableLegacyTextComponentCache=<ab-variant>"
jvm_arguments=(
  -Xms2G
  -Xmx2G
  -XX:+UseG1GC
  -XX:+AlwaysPreTouch
  -Dinteractionvisualizer.performance.allowBlockScene=true
  "$legacy_text_cache_jvm_argument"
  "$jvm_gc_safepoint_xlog"
  -Dfile.encoding=UTF-8
)
legacy_text_cache_jvm_argument_index=5
[[ "${jvm_arguments[$legacy_text_cache_jvm_argument_index]}" == "$legacy_text_cache_jvm_argument" ]] || {
  echo "legacy text cache JVM treatment index is stale" >&2
  exit 1
}
jvm_arguments_normalized=("${jvm_arguments[@]}")
jvm_arguments_normalized[$legacy_text_cache_jvm_argument_index]="$legacy_text_cache_jvm_argument_template"
jvm_arguments_fingerprint="$(IFS=' '; printf '%s' "${jvm_arguments[*]}")"
jvm_arguments_sha256="$(printf '%s' "$jvm_arguments_fingerprint" | sha256sum | awk '{print $1}')"
jvm_arguments_normalized_fingerprint="$(IFS=' '; printf '%s' "${jvm_arguments_normalized[*]}")"
jvm_arguments_normalized_sha256="$(printf '%s' "$jvm_arguments_normalized_fingerprint" | sha256sum | awk '{print $1}')"

cp "$paper_jar" "$run_directory/server.jar"
cp "$plugin_jar" "$run_directory/plugins/InteractionVisualizer.jar"
unzip -p "$plugin_jar" config.yml > "$run_directory/plugins/InteractionVisualizer/config.yml"

python3 - "$run_directory/plugins/InteractionVisualizer/config.yml" "$scenario" "$variant" "$ab_factor" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
scenario = sys.argv[2]
variant = sys.argv[3]
ab_factor = sys.argv[4]
text = path.read_text(encoding="utf-8")

def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one config token {old!r}, found {count}")
    text = text.replace(old, new, 1)

def replace_boolean_once(prefix: str, value: bool) -> None:
    global text
    pattern = re.compile(rf"(?m)^{re.escape(prefix)}(?:true|false)$")
    replacement = f"{prefix}{str(value).lower()}"
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(
            f"Expected one boolean config token with prefix {prefix!r}, found {count}"
        )

packet_only = scenario == "visibility-return" or (
    scenario in {"static-steady", "static-spawn"} and variant == "B"
)
visibility_limit = scenario.startswith("visibility-") and variant == "B"
event_driven = scenario.startswith("block-") and (
    ab_factor == "legacy-text-component-cache" or variant == "B"
)
source_owned_candidates = (
    ab_factor == "dropped-item-section-candidates" and variant == "B"
)
replace_boolean_once("      PacketOnlyStatic: ", packet_only)
replace_boolean_once("      Enabled: ", visibility_limit)
replace_boolean_once("      EventDriven: ", event_driven)
replace_boolean_once(
    "        SourceOwnedSectionCandidates: ", source_owned_candidates
)
if scenario == "dropped-items":
    replace_once("      DespawnTicks: 6000", "      DespawnTicks: 12000")
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
if [[ -n "${JAVA_TOOL_OPTIONS:-}" || -n "${_JAVA_OPTIONS:-}" || -n "${JDK_JAVA_OPTIONS:-}" ]]; then
  echo "Inherited Java option environment variables would invalidate JVM argument provenance" >&2
  exit 1
fi
(
  cd "$run_directory"
  exec java "${jvm_arguments[@]}" -jar server.jar --nogui < console.pipe
) > "$server_log" 2>&1 &
server_pid=$!

wait_for_log "$(plugin_enabled_log_marker "$paper_version")" 240
wait_for_log "Done (" 240

python3 - "/proc/$server_pid/cmdline" "$jvm_command_line_metadata" "$server_pid" \
  "$jvm_arguments_fingerprint" "$jvm_arguments_sha256" \
  "$jvm_arguments_normalized_fingerprint" "$jvm_arguments_normalized_sha256" <<'PY'
from pathlib import Path
import hashlib
import json
import sys

(
    proc_path_text,
    output_path_text,
    process_id_text,
    expected_fingerprint,
    expected_sha256,
    normalized_fingerprint,
    normalized_sha256,
) = sys.argv[1:]
proc_path = Path(proc_path_text)
output_path = Path(output_path_text)
raw = proc_path.read_bytes()
if not raw or not raw.endswith(b"\0"):
    raise SystemExit("live JVM /proc cmdline is missing or malformed")
try:
    arguments = [part.decode("utf-8") for part in raw[:-1].split(b"\0")]
except UnicodeDecodeError as error:
    raise SystemExit("live JVM /proc cmdline is not UTF-8") from error
if not arguments or arguments.count("-jar") != 1:
    raise SystemExit(f"live JVM command line must have exactly one -jar boundary: {arguments!r}")
jar_index = arguments.index("-jar")
jvm_arguments = arguments[1:jar_index]
application_arguments = arguments[jar_index:]
expected_arguments = expected_fingerprint.split(" ")
if jvm_arguments != expected_arguments:
    raise SystemExit(
        "live JVM arguments differ from the treatment fingerprint: "
        f"expected={expected_arguments!r} actual={jvm_arguments!r}"
    )
if application_arguments != ["-jar", "server.jar", "--nogui"]:
    raise SystemExit(f"unexpected Java application arguments: {application_arguments!r}")
actual_fingerprint = " ".join(jvm_arguments)
actual_sha256 = hashlib.sha256(actual_fingerprint.encode("utf-8")).hexdigest()
if actual_sha256 != expected_sha256:
    raise SystemExit("live JVM argument SHA does not match the expected treatment SHA")
if hashlib.sha256(normalized_fingerprint.encode("utf-8")).hexdigest() != normalized_sha256:
    raise SystemExit("normalized JVM argument SHA does not match its fingerprint")
metadata = {
    "schemaVersion": 1,
    "formalEvidenceReady": True,
    "capturedFromProcCmdline": True,
    "processId": int(process_id_text),
    "executable": arguments[0],
    "jvmArguments": jvm_arguments,
    "jvmArgumentsFingerprint": actual_fingerprint,
    "jvmArgumentsSha256": actual_sha256,
    "jvmArgumentsNormalizedFingerprint": normalized_fingerprint,
    "jvmArgumentsNormalizedSha256": normalized_sha256,
    "applicationArguments": application_arguments,
    "inheritedJavaOptionEnvironment": False,
    "rawCmdlineSha256": hashlib.sha256(raw).hexdigest(),
}
output_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
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
  protocol_trace_environment=(
    "PHASE2_PROTOCOL_TRACE_FILE=$protocol_trace_path"
    "PHASE2_PROTOCOL_TRACE_MAX_EVENTS=$protocol_trace_max_events"
    "PHASE2_PROTOCOL_TRACE_PACKET_ALLOWLIST=$protocol_trace_packet_allowlist"
    "PHASE2_PROTOCOL_TRACE_AGGREGATE_PACKET_ALLOWLIST=$protocol_trace_aggregate_packet_allowlist"
  )
fi
if [[ "$ab_factor" == dropped-item-section-candidates && "$scenario" != dropped-items ]]; then
  echo "dropped-item-section-candidates A/B is isolated to dropped-items" >&2
  exit 64
fi
if [[ "$scenario" == dropped-items && "$ab_factor" != dropped-item-section-candidates ]]; then
  echo "dropped-items is reserved for the dropped-item-section-candidates factor" >&2
  exit 64
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
  dropped-items)
    scene_type=dropped
    ;;
  esac
  scene_spawn_log="Spawned $item_count $scene_type benchmark $scene_entity_label"

  if [[ "$scenario" == dropped-items ]]; then
    send_console "iv perf scene $scene_type $item_count $lifetime_ticks IVBench $dropped_nearby_item_count"
  else
    send_console "iv perf scene $scene_type $item_count $lifetime_ticks IVBench"
  fi
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

spark_profile_start_command=""
spark_profile_interval=""
spark_profile_interval_unit=""
spark_profile_only_ticks_over_ms=""
if [[ "$spark_profile_mode" != none ]]; then
  spark_start_pattern="Profiler is now running!"
  case "$spark_profile_mode" in
    cpu)
      spark_profile_start_command="spark profiler start --interval 1 --only-ticks-over 40"
      spark_profile_interval="1"
      spark_profile_interval_unit="milliseconds"
      spark_profile_only_ticks_over_ms="40"
      ;;
    cpu-all)
      spark_profile_start_command="spark profiler start --interval 1"
      spark_profile_interval="1"
      spark_profile_interval_unit="milliseconds"
      spark_profile_only_ticks_over_ms=""
      ;;
    alloc)
      spark_profile_start_command="spark profiler start --alloc --interval 32768"
      spark_profile_interval="32768"
      spark_profile_interval_unit="bytes"
      spark_profile_only_ticks_over_ms=""
      spark_start_pattern="Allocation Profiler is now running!"
      ;;
  esac
  spark_start_count="$(grep -Fc -- "$spark_start_pattern" "$server_log" 2>/dev/null || true)"
  send_console "$spark_profile_start_command"
  wait_for_log_count "$spark_start_pattern" "$((spark_start_count + 1))" 60
  spark_start_record="$(grep -F -- "$spark_start_pattern" "$server_log" | tail -n 1)"
  if [[ "$spark_start_record" != *"(async)"* ]]; then
    echo "Spark did not start the async-profiler engine: $spark_start_record" >&2
    exit 1
  fi
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

if [[ "$spark_profile_mode" != none ]]; then
  spark_stop_pattern="Profiler stopped & save complete!"
  spark_stop_count="$(grep -Fc -- "$spark_stop_pattern" "$server_log" 2>/dev/null || true)"
  spark_profile_stop_command="spark profiler stop --save-to-file --comment phase2-$run_id-$spark_profile_mode"
  send_console "$spark_profile_stop_command"
  wait_for_log_count "$spark_stop_pattern" "$((spark_stop_count + 1))" 120
  mapfile -t spark_profile_candidates < <(
    find "$run_directory/plugins" -type f -name '*.sparkprofile' -print
  )
  if [[ "${#spark_profile_candidates[@]}" != 1 ]]; then
    echo "Expected exactly one saved Spark profile, found ${#spark_profile_candidates[@]}" >&2
    printf '%s\n' "${spark_profile_candidates[@]}" >&2
    exit 1
  fi
  mv -- "${spark_profile_candidates[0]}" "$spark_profile_output"
  test -s "$spark_profile_output"
  spark_profile_sha="$(sha256sum "$spark_profile_output" | awk '{print $1}')"
  spark_profile_size="$(stat -c '%s' "$spark_profile_output")"
  python3 - \
    "$spark_profile_metadata" \
    "$spark_profile_mode" \
    "$(basename "$spark_profile_output")" \
    "$spark_profile_sha" \
    "$spark_profile_size" \
    "$spark_profile_start_command" \
    "$spark_profile_stop_command" \
    "$spark_profile_interval" \
    "$spark_profile_interval_unit" \
    "$spark_profile_only_ticks_over_ms" <<'PY'
from pathlib import Path
import json
import sys

(
    output_path,
    mode,
    profile_name,
    profile_sha,
    profile_size,
    start_command,
    stop_command,
    interval,
    interval_unit,
    only_ticks_over_ms,
) = sys.argv[1:]

metadata = {
    "schemaVersion": 1,
    "profileEvidenceReady": True,
    "performanceEvidenceReady": False,
    "mode": mode,
    "engine": "async-profiler",
    "startCommand": start_command,
    "stopCommand": stop_command,
    "sampling": {
        "interval": float(interval),
        "intervalUnit": interval_unit,
        "onlyTicksOverMs": int(only_ticks_over_ms) if only_ticks_over_ms else None,
    },
    "profile": {
        "path": profile_name,
        "sha256": profile_sha,
        "sizeBytes": int(profile_size),
    },
}
Path(output_path).write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY
fi

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

python3 - "$server_log" "$run_id" "$run_directory/iv-perf.json" "$scenario" "$variant" \
  "$item_count" "$dropped_nearby_item_count" "$ab_factor" "$legacy_text_cache_disable_property" \
  "$legacy_text_cache_enabled" "$jvm_arguments_sha256" \
  "$jvm_arguments_normalized_sha256" <<'PY'
import json
import math
import sys
(
    log_path,
    run_id,
    output_path,
    scenario,
    variant,
    item_count_text,
    dropped_nearby_item_count_text,
    ab_factor,
    disable_property_text,
    expected_cache_enabled_text,
    jvm_arguments_sha256,
    jvm_arguments_normalized_sha256,
) = sys.argv[1:]
item_count = int(item_count_text)
dropped_nearby_item_count = int(dropped_nearby_item_count_text)
disable_property = disable_property_text == "true"
expected_cache_enabled = expected_cache_enabled_text == "true"
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
expected_event_driven = block_scenario and (
    ab_factor == "legacy-text-component-cache" or variant == "B"
)
expected_dropped_source = (
    ab_factor == "dropped-item-section-candidates" and variant == "B"
)
if metrics.get("packetOnlyStatic") is not expected_packet_only:
    raise SystemExit(f"packetOnlyStatic does not match {scenario}/{variant}")
if metrics.get("visibilityRateLimit") is not expected_limiter:
    raise SystemExit(f"visibilityRateLimit does not match {scenario}/{variant}")
if metrics.get("eventDrivenBlockUpdates") is not expected_event_driven:
    raise SystemExit(f"eventDrivenBlockUpdates does not match {scenario}/{variant}")
if metrics.get("droppedSourceOwnedSectionCandidates") is not expected_dropped_source:
    raise SystemExit(
        f"droppedSourceOwnedSectionCandidates does not match {scenario}/{variant}"
    )
if metrics.get("legacyTextComponentCache") is not expected_cache_enabled:
    raise SystemExit(
        "legacyTextComponentCache does not match the injected disable property: "
        f"property={disable_property} enabled={metrics.get('legacyTextComponentCache')!r}"
    )
cache_integer_fields = (
    "legacyTextCacheRequests",
    "legacyTextCacheMisses",
    "legacyTextCacheHits",
    "legacyTextSameRawFastPaths",
)
for field in cache_integer_fields:
    value = metrics.get(field)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise SystemExit(f"invalid {field}={value!r}")
cache_requests = metrics["legacyTextCacheRequests"]
cache_misses = metrics["legacyTextCacheMisses"]
cache_hits = metrics["legacyTextCacheHits"]
cache_hit_rate = metrics.get("legacyTextCacheHitRate")
if cache_misses > cache_requests or cache_hits != cache_requests - cache_misses:
    raise SystemExit(
        "legacy text cache request/miss/hit counters are inconsistent: "
        f"requests={cache_requests} misses={cache_misses} hits={cache_hits}"
    )
if (isinstance(cache_hit_rate, bool) or not isinstance(cache_hit_rate, (int, float))
        or not math.isfinite(cache_hit_rate) or not 0.0 <= cache_hit_rate <= 1.0):
    raise SystemExit(f"invalid legacyTextCacheHitRate={cache_hit_rate!r}")
derived_hit_rate = cache_hits / cache_requests if cache_requests else 0.0
if not math.isclose(cache_hit_rate, derived_hit_rate, rel_tol=0.0, abs_tol=0.000001):
    raise SystemExit(
        "legacy text cache hit rate disagrees with its counters: "
        f"recorded={cache_hit_rate} derived={derived_hit_rate}"
    )
if ab_factor == "legacy-text-component-cache":
    if cache_requests <= 0:
        raise SystemExit("cache A/B workload did not exercise legacy text parsing")
    if disable_property and cache_misses != cache_requests:
        raise SystemExit("disabled legacy text cache unexpectedly reported hits")
    if not disable_property and cache_hit_rate < 0.90:
        raise SystemExit(
            "enabled legacy text cache missed the 90% steady-state hit-rate guard: "
            f"hitRate={cache_hit_rate:.6f}"
        )
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
if scenario == "dropped-items":
    dropped_ms = metrics.get("droppedItemMs")
    distance_checks = metrics.get("droppedViewerDistanceChecks")
    spatial_candidates = metrics.get("droppedSpatialCandidates")
    full_scan_candidates = metrics.get("droppedFullScanCandidates")
    tracked_max = metrics.get("droppedTrackedItemsMax")
    labels_max = metrics.get("droppedLabelsMax")
    if (isinstance(dropped_ms, bool) or not isinstance(dropped_ms, (int, float))
            or not math.isfinite(dropped_ms) or dropped_ms <= 0):
        raise SystemExit(f"invalid droppedItemMs={dropped_ms!r}")
    for field, value in (
        ("droppedViewerDistanceChecks", distance_checks),
        ("droppedSpatialCandidates", spatial_candidates),
        ("droppedFullScanCandidates", full_scan_candidates),
        ("droppedTrackedItemsMax", tracked_max),
        ("droppedLabelsMax", labels_max),
    ):
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise SystemExit(f"invalid {field}={value!r}")
    if distance_checks <= 0 or spatial_candidates <= 0:
        raise SystemExit("dropped-item workload performed no candidate checks")
    if tracked_max != item_count or labels_max != dropped_nearby_item_count:
        raise SystemExit(
            "dropped-item workload did not retain its global/local split: "
            f"tracked={tracked_max}/{item_count} "
            f"labels={labels_max}/{dropped_nearby_item_count}"
        )
    if variant == "A" and full_scan_candidates <= 0:
        raise SystemExit("legacy dropped-item path performed no full candidate scans")
    if variant == "B" and full_scan_candidates != 0:
        raise SystemExit(
            f"section-candidate path performed {full_scan_candidates} full candidate scans"
        )
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
metrics["abFactor"] = ab_factor
metrics["legacyTextComponentCacheDisableProperty"] = disable_property
metrics["jvmArgumentsSha256"] = jvm_arguments_sha256
metrics["jvmArgumentsNormalizedSha256"] = jvm_arguments_normalized_sha256
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
    mutation_write_ms = float(mutate["fields"]["mutationWriteMs"])
    mutation_inspection_ms = float(mutate["fields"]["mutationInspectionMs"])
    mutation_preflight_ms = float(mutate["fields"]["mutationPreflightMs"])
    mutation_command_ms = float(mutate["fields"]["mutationCommandMs"])
    if mutation_start_tick < 0 or mutation_end_tick != mutation_start_tick:
        raise SystemExit(
            f"direct-write mutation did not complete synchronously in one Bukkit tick: "
            f"start={mutation_start_tick} end={mutation_end_tick}"
        )
    if not math.isfinite(mutation_elapsed_ms) or mutation_elapsed_ms <= 0:
        raise SystemExit(f"invalid direct-write mutationElapsedMs={mutation_elapsed_ms!r}")
    if (not math.isfinite(mutation_write_ms) or mutation_write_ms <= 0
            or mutation_write_ms > mutation_elapsed_ms):
        raise SystemExit(f"invalid direct-write mutationWriteMs={mutation_write_ms!r}")
    if (not math.isfinite(mutation_inspection_ms) or mutation_inspection_ms <= 0
            or mutation_inspection_ms > mutation_elapsed_ms):
        raise SystemExit(f"invalid direct-write mutationInspectionMs={mutation_inspection_ms!r}")
    if (not math.isfinite(mutation_command_ms) or mutation_command_ms <= 0
            or mutation_elapsed_ms > mutation_command_ms):
        raise SystemExit(f"invalid direct-write mutationCommandMs={mutation_command_ms!r}")
    if (not math.isfinite(mutation_preflight_ms) or mutation_preflight_ms <= 0
            or mutation_preflight_ms > mutation_command_ms):
        raise SystemExit(f"invalid direct-write mutationPreflightMs={mutation_preflight_ms!r}")
    timing_fields = (
        "mutationStartBukkitTick",
        "mutationEndBukkitTick",
        "mutationElapsedMs",
        "mutationWriteMs",
        "mutationInspectionMs",
    )
    for field in timing_fields:
        if clear["fields"].get(field) != mutate["fields"].get(field):
            raise SystemExit(f"clear record did not preserve direct-write timing field {field}")
    slowest_tick = int(metrics["msptMaxBukkitTick"])
    slowest_epoch_ms = int(metrics["msptMaxEndEpochMillis"])
    slowest_block_checks = int(metrics["msptMaxBlockUpdateChecks"])
    slowest_block_ms = float(metrics["msptMaxBlockUpdateMs"])
    slowest_ms = float(metrics["msptMax"])
    if not math.isfinite(slowest_ms) or slowest_ms <= 0:
        raise SystemExit(f"invalid direct-write msptMax={slowest_ms!r}")
    if mutation_command_ms > slowest_ms:
        raise SystemExit(
            f"direct-write command duration exceeds its completed tick: "
            f"command={mutation_command_ms!r} tick={slowest_ms!r}"
        )
    mutation_unattributed_ms = max(
        0.0, mutation_elapsed_ms - mutation_write_ms - mutation_inspection_ms
    )
    mutation_command_unattributed_ms = max(
        0.0, mutation_command_ms - mutation_preflight_ms - mutation_elapsed_ms
    )
    direct_write_diagnostics = {
        "schemaVersion": 2,
        "mutationStartBukkitTick": mutation_start_tick,
        "mutationEndBukkitTick": mutation_end_tick,
        "mutationElapsedMs": mutation_elapsed_ms,
        "mutationWriteMs": mutation_write_ms,
        "mutationInspectionMs": mutation_inspection_ms,
        "mutationUnattributedMs": mutation_unattributed_ms,
        "mutationPreflightMs": mutation_preflight_ms,
        "mutationCommandMs": mutation_command_ms,
        "mutationCommandUnattributedMs": mutation_command_unattributed_ms,
        "slowestBukkitTick": slowest_tick,
        "slowestTickEndEpochMillis": slowest_epoch_ms,
        "slowestTickMs": slowest_ms,
        "slowestTickBlockUpdateChecks": slowest_block_checks,
        "slowestTickBlockUpdateMs": slowest_block_ms,
        "slowestTickWithinMutation": mutation_start_tick <= slowest_tick <= mutation_end_tick,
        "mutationFractionOfSlowestTick": (
            mutation_elapsed_ms / slowest_ms if slowest_ms > 0 else None
        ),
        "mutationWriteFractionOfSlowestTick": (
            mutation_write_ms / slowest_ms if slowest_ms > 0 else None
        ),
        "mutationInspectionFractionOfSlowestTick": (
            mutation_inspection_ms / slowest_ms if slowest_ms > 0 else None
        ),
        "mutationPreflightFractionOfSlowestTick": (
            mutation_preflight_ms / slowest_ms if slowest_ms > 0 else None
        ),
        "mutationCommandFractionOfSlowestTick": (
            mutation_command_ms / slowest_ms if slowest_ms > 0 else None
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
    "schemaVersion": 3,
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
  "$jvm_command_line_metadata" \
  "$jvm_gc_safepoint_log_name" \
  "$jvm_arguments_fingerprint" \
  "$jvm_arguments_sha256" \
  "$jvm_arguments_normalized_fingerprint" \
  "$jvm_arguments_normalized_sha256" \
  "$jvm_gc_safepoint_xlog" <<'PY'
from pathlib import Path
import hashlib
import json
import re
import sys

log_path = Path(sys.argv[1])
metadata_path = Path(sys.argv[2])
command_line_metadata_path = Path(sys.argv[3])
log_name = sys.argv[4]
jvm_arguments_fingerprint = sys.argv[5]
jvm_arguments_sha256 = sys.argv[6]
jvm_arguments_normalized_fingerprint = sys.argv[7]
jvm_arguments_normalized_sha256 = sys.argv[8]
unified_logging_option = sys.argv[9]

if hashlib.sha256(jvm_arguments_fingerprint.encode("utf-8")).hexdigest() != jvm_arguments_sha256:
    raise SystemExit("JVM argument SHA does not match the exact argument fingerprint")
if (hashlib.sha256(jvm_arguments_normalized_fingerprint.encode("utf-8")).hexdigest()
        != jvm_arguments_normalized_sha256):
    raise SystemExit("normalized JVM argument SHA does not match its fingerprint")

if not command_line_metadata_path.is_file():
    raise SystemExit(f"live JVM command-line metadata is missing: {command_line_metadata_path}")
command_line_metadata_raw = command_line_metadata_path.read_bytes()
command_line_metadata = json.loads(command_line_metadata_raw.decode("utf-8"))
if (command_line_metadata.get("formalEvidenceReady") is not True
        or command_line_metadata.get("capturedFromProcCmdline") is not True):
    raise SystemExit("live JVM command-line metadata is not formal-evidence ready")
if command_line_metadata.get("jvmArgumentsFingerprint") != jvm_arguments_fingerprint:
    raise SystemExit("live JVM command-line fingerprint differs from the expected fingerprint")
if command_line_metadata.get("jvmArgumentsSha256") != jvm_arguments_sha256:
    raise SystemExit("live JVM command-line SHA differs from the expected SHA")
if (command_line_metadata.get("jvmArgumentsNormalizedFingerprint")
        != jvm_arguments_normalized_fingerprint):
    raise SystemExit("live JVM normalized argument fingerprint mismatch")
if (command_line_metadata.get("jvmArgumentsNormalizedSha256")
        != jvm_arguments_normalized_sha256):
    raise SystemExit("live JVM normalized argument SHA mismatch")

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
    "schemaVersion": 3,
    "formalEvidenceReady": True,
    "finalizedAfterServerExit": True,
    "serverStoppedCleanly": True,
    "jvmArgumentsFingerprint": jvm_arguments_fingerprint,
    "jvmArgumentsSha256": jvm_arguments_sha256,
    "jvmArgumentsNormalizedFingerprint": jvm_arguments_normalized_fingerprint,
    "jvmArgumentsNormalizedSha256": jvm_arguments_normalized_sha256,
    "unifiedLoggingOption": unified_logging_option,
    "processCommandLine": {
        **command_line_metadata,
        "metadataPath": "jvm-command-line.json",
        "metadataSha256": hashlib.sha256(command_line_metadata_raw).hexdigest(),
    },
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
test -s "$jvm_command_line_metadata"
jvm_gc_safepoint_sha="$(sha256sum "$jvm_gc_safepoint_log" | awk '{print $1}')"
jvm_command_line_metadata_sha="$(sha256sum "$jvm_command_line_metadata" | awk '{print $1}')"
python3 - \
  "$jvm_diagnostics_metadata" \
  "$jvm_command_line_metadata" \
  "$jvm_command_line_metadata_sha" \
  "$jvm_gc_safepoint_sha" \
  "$jvm_arguments_fingerprint" \
  "$jvm_arguments_sha256" \
  "$jvm_arguments_normalized_fingerprint" \
  "$jvm_arguments_normalized_sha256" \
  "$jvm_gc_safepoint_xlog" <<'PY'
import json
import sys

(
    metadata_path,
    command_line_metadata_path,
    expected_command_line_metadata_sha,
    expected_log_sha,
    expected_arguments,
    expected_arguments_sha,
    expected_normalized_arguments,
    expected_normalized_arguments_sha,
    expected_logging_option,
) = sys.argv[1:]
with open(metadata_path, encoding="utf-8") as stream:
    metadata = json.load(stream)
with open(command_line_metadata_path, encoding="utf-8") as stream:
    command_line_metadata = json.load(stream)
if metadata.get("formalEvidenceReady") is not True:
    raise SystemExit("JVM diagnostic metadata is not formal-evidence ready")
if metadata.get("finalizedAfterServerExit") is not True:
    raise SystemExit("JVM diagnostic metadata was not finalized after server exit")
if metadata.get("jvmArgumentsFingerprint") != expected_arguments:
    raise SystemExit("JVM argument fingerprint mismatch in diagnostic metadata")
if metadata.get("jvmArgumentsSha256") != expected_arguments_sha:
    raise SystemExit("JVM argument SHA mismatch in diagnostic metadata")
if metadata.get("jvmArgumentsNormalizedFingerprint") != expected_normalized_arguments:
    raise SystemExit("normalized JVM argument fingerprint mismatch in diagnostic metadata")
if metadata.get("jvmArgumentsNormalizedSha256") != expected_normalized_arguments_sha:
    raise SystemExit("normalized JVM argument SHA mismatch in diagnostic metadata")
embedded_command_line = metadata.get("processCommandLine")
if not isinstance(embedded_command_line, dict):
    raise SystemExit("JVM diagnostic metadata has no process command-line evidence")
if embedded_command_line.get("metadataPath") != "jvm-command-line.json":
    raise SystemExit("JVM command-line metadata path mismatch")
if embedded_command_line.get("metadataSha256") != expected_command_line_metadata_sha:
    raise SystemExit("JVM command-line metadata SHA mismatch")
embedded_command_line = dict(embedded_command_line)
embedded_command_line.pop("metadataPath")
embedded_command_line.pop("metadataSha256")
if embedded_command_line != command_line_metadata:
    raise SystemExit("embedded JVM command-line evidence differs from jvm-command-line.json")
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
spark_profile_manifest_json="$(python3 - \
  "$spark_profile_mode" \
  "$spark_profile_metadata" \
  "$spark_profile_output" <<'PY'
from pathlib import Path
import hashlib
import json
import sys

mode = sys.argv[1]
metadata_path = Path(sys.argv[2])
profile_path = Path(sys.argv[3])
if mode == "none":
    if metadata_path.exists() or profile_path.exists():
        raise SystemExit("disabled Spark profiling unexpectedly produced evidence")
    print(json.dumps({
        "enabled": False,
        "mode": "none",
        "profileEvidenceReady": None,
        "performanceEvidenceReady": True,
        "metadataPath": None,
        "metadataSha256": None,
    }, separators=(",", ":")))
    raise SystemExit(0)

if not metadata_path.is_file() or not profile_path.is_file():
    raise SystemExit("enabled Spark profiling did not produce complete evidence")
with metadata_path.open(encoding="utf-8") as stream:
    metadata = json.load(stream)
raw_profile = profile_path.read_bytes()
if not raw_profile:
    raise SystemExit("saved Spark profile is empty")
profile = metadata.get("profile", {})
if metadata.get("profileEvidenceReady") is not True:
    raise SystemExit("Spark profile metadata is not profile-evidence ready")
if metadata.get("performanceEvidenceReady") is not False:
    raise SystemExit("instrumented Spark evidence must not claim clean performance readiness")
if metadata.get("mode") != mode or metadata.get("engine") != "async-profiler":
    raise SystemExit("Spark profile metadata mode/engine mismatch")
if profile.get("path") != profile_path.name:
    raise SystemExit("Spark profile metadata path mismatch")
if profile.get("sha256") != hashlib.sha256(raw_profile).hexdigest():
    raise SystemExit("Spark profile metadata SHA mismatch")
if profile.get("sizeBytes") != len(raw_profile):
    raise SystemExit("Spark profile metadata size mismatch")
metadata["enabled"] = True
metadata["metadataPath"] = metadata_path.name
metadata["metadataSha256"] = hashlib.sha256(metadata_path.read_bytes()).hexdigest()
print(json.dumps(metadata, ensure_ascii=False, separators=(",", ":")))
PY
)"
legacy_text_cache_manifest_json="$(python3 - \
  "$run_directory/iv-perf.json" \
  "$ab_factor" \
  "$legacy_text_cache_disable_property" \
  "$legacy_text_cache_enabled" <<'PY'
import json
import sys

metrics_path, ab_factor, disable_property_text, expected_enabled_text = sys.argv[1:]
with open(metrics_path, encoding="utf-8") as stream:
    metrics = json.load(stream)
disable_property = disable_property_text == "true"
expected_enabled = expected_enabled_text == "true"
if metrics.get("abFactor") != ab_factor:
    raise SystemExit("IV_PERF A/B factor drifted before manifest creation")
if metrics.get("legacyTextComponentCacheDisableProperty") is not disable_property:
    raise SystemExit("IV_PERF legacy text cache property drifted before manifest creation")
if metrics.get("legacyTextComponentCache") is not expected_enabled:
    raise SystemExit("IV_PERF legacy text cache state drifted before manifest creation")
print(json.dumps({
    "propertyName": "interactionvisualizer.disableLegacyTextComponentCache",
    "disableProperty": disable_property,
    "enabled": metrics["legacyTextComponentCache"],
    "requests": metrics["legacyTextCacheRequests"],
    "misses": metrics["legacyTextCacheMisses"],
    "hits": metrics["legacyTextCacheHits"],
    "hitRate": metrics["legacyTextCacheHitRate"],
    "sameRawFastPaths": metrics["legacyTextSameRawFastPaths"],
}, ensure_ascii=False, separators=(",", ":")))
PY
)"

dropped_nearby_manifest=null
if [[ "$scenario" == dropped-items ]]; then
  dropped_nearby_manifest="$dropped_nearby_item_count"
fi

cat > "$run_directory/run-manifest.json" <<EOF
{
  "schemaVersion": 7,
  "runId": "$run_id",
  "scenario": "$scenario",
  "paperVersion": "$paper_version",
  "paperChannel": "$paper_channel",
  "paperBuildId": $paper_build_id,
  "variant": "$variant",
  "abFactor": "$ab_factor",
  "droppedSourceOwnedSectionCandidates": $dropped_source_owned_candidates,
  "blockSceneMutationOptIn": true,
  "captureEnabled": $([[ "$capture_enabled" == 1 ]] && echo true || echo false),
  "captureSnaplen": $capture_snaplen,
  "serverPort": $server_port,
  "itemCount": $item_count,
  "droppedNearbyItemCount": $dropped_nearby_manifest,
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
  "jvmArgumentsSha256": "$jvm_arguments_sha256",
  "jvmArgumentsNormalizedSha256": "$jvm_arguments_normalized_sha256",
  "legacyTextComponentCache": $legacy_text_cache_manifest_json,
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
  "jvmDiagnostics": $jvm_diagnostics_manifest_json,
  "sparkProfile": $spark_profile_manifest_json
}
EOF

python3 - \
  "$run_directory/run-manifest.json" \
  "$jvm_diagnostics_metadata" \
  "$jvm_diagnostics_metadata_sha" \
  "$jvm_gc_safepoint_sha" \
  "$run_directory/iv-perf.json" \
  "$ab_factor" \
  "$legacy_text_cache_disable_property" \
  "$legacy_text_cache_enabled" \
  "$jvm_arguments_sha256" \
  "$jvm_arguments_normalized_sha256" \
  "$spark_profile_mode" \
  "$spark_profile_metadata" \
  "$spark_profile_output" <<'PY'
from pathlib import Path
import hashlib
import json
import sys

manifest_path = Path(sys.argv[1])
metadata_path = Path(sys.argv[2])
expected_metadata_sha = sys.argv[3]
expected_log_sha = sys.argv[4]
metrics_path = Path(sys.argv[5])
expected_ab_factor = sys.argv[6]
expected_disable_property = sys.argv[7] == "true"
expected_cache_enabled = sys.argv[8] == "true"
expected_jvm_arguments_sha = sys.argv[9]
expected_jvm_arguments_normalized_sha = sys.argv[10]
spark_mode = sys.argv[11]
spark_metadata_path = Path(sys.argv[12])
spark_profile_path = Path(sys.argv[13])
with manifest_path.open(encoding="utf-8") as stream:
    manifest = json.load(stream)
with metadata_path.open(encoding="utf-8") as stream:
    metadata = json.load(stream)
with metrics_path.open(encoding="utf-8") as stream:
    metrics = json.load(stream)

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
if manifest.get("abFactor") != expected_ab_factor:
    raise SystemExit("run manifest has the wrong A/B factor")
expected_dropped_source = (
    expected_ab_factor == "dropped-item-section-candidates"
    and manifest.get("variant") == "B"
)
if manifest.get("droppedSourceOwnedSectionCandidates") is not expected_dropped_source:
    raise SystemExit("run manifest has the wrong dropped-item candidate source")
if metrics.get("droppedSourceOwnedSectionCandidates") is not expected_dropped_source:
    raise SystemExit("IV_PERF evidence has the wrong dropped-item candidate source")
if manifest.get("jvmArgumentsSha256") != expected_jvm_arguments_sha:
    raise SystemExit("run manifest has the wrong JVM argument SHA")
if embedded.get("jvmArgumentsSha256") != expected_jvm_arguments_sha:
    raise SystemExit("JVM diagnostics have the wrong JVM argument SHA")
if metrics.get("jvmArgumentsSha256") != expected_jvm_arguments_sha:
    raise SystemExit("IV_PERF evidence has the wrong JVM argument SHA")
if manifest.get("jvmArgumentsNormalizedSha256") != expected_jvm_arguments_normalized_sha:
    raise SystemExit("run manifest has the wrong normalized JVM argument SHA")
if embedded.get("jvmArgumentsNormalizedSha256") != expected_jvm_arguments_normalized_sha:
    raise SystemExit("JVM diagnostics have the wrong normalized JVM argument SHA")
if metrics.get("jvmArgumentsNormalizedSha256") != expected_jvm_arguments_normalized_sha:
    raise SystemExit("IV_PERF evidence has the wrong normalized JVM argument SHA")
process_command_line = embedded.get("processCommandLine")
if not isinstance(process_command_line, dict):
    raise SystemExit("JVM diagnostics have no live process command-line evidence")
if (process_command_line.get("formalEvidenceReady") is not True
        or process_command_line.get("capturedFromProcCmdline") is not True):
    raise SystemExit("live JVM process command-line evidence is not formal-evidence ready")
if process_command_line.get("jvmArgumentsSha256") != expected_jvm_arguments_sha:
    raise SystemExit("live JVM process command line has the wrong JVM argument SHA")
if (process_command_line.get("jvmArgumentsNormalizedSha256")
        != expected_jvm_arguments_normalized_sha):
    raise SystemExit("live JVM process command line has the wrong normalized JVM argument SHA")
embedded_cache = manifest.get("legacyTextComponentCache")
if not isinstance(embedded_cache, dict):
    raise SystemExit("run manifest has no legacy text cache provenance")
expected_cache = {
    "propertyName": "interactionvisualizer.disableLegacyTextComponentCache",
    "disableProperty": expected_disable_property,
    "enabled": expected_cache_enabled,
    "requests": metrics.get("legacyTextCacheRequests"),
    "misses": metrics.get("legacyTextCacheMisses"),
    "hits": metrics.get("legacyTextCacheHits"),
    "hitRate": metrics.get("legacyTextCacheHitRate"),
    "sameRawFastPaths": metrics.get("legacyTextSameRawFastPaths"),
}
if embedded_cache != expected_cache:
    raise SystemExit("run manifest legacy text cache provenance does not match IV_PERF")

embedded_spark = manifest.get("sparkProfile")
if not isinstance(embedded_spark, dict):
    raise SystemExit("run manifest has no Spark profile state")
if spark_mode == "none":
    if (embedded_spark.get("enabled") is not False
            or embedded_spark.get("mode") != "none"
            or embedded_spark.get("performanceEvidenceReady") is not True):
        raise SystemExit("run manifest incorrectly enables Spark profiling")
else:
    if embedded_spark.get("enabled") is not True or embedded_spark.get("mode") != spark_mode:
        raise SystemExit("run manifest Spark profile mode mismatch")
    if (embedded_spark.get("profileEvidenceReady") is not True
            or embedded_spark.get("performanceEvidenceReady") is not False):
        raise SystemExit("run manifest Spark evidence-readiness mismatch")
    with spark_metadata_path.open(encoding="utf-8") as stream:
        spark_metadata = json.load(stream)
    spark_metadata_sha = hashlib.sha256(spark_metadata_path.read_bytes()).hexdigest()
    if embedded_spark.get("metadataPath") != spark_metadata_path.name:
        raise SystemExit("run manifest Spark metadata path mismatch")
    if embedded_spark.get("metadataSha256") != spark_metadata_sha:
        raise SystemExit("run manifest Spark metadata SHA mismatch")
    embedded_spark_metadata = dict(embedded_spark)
    embedded_spark_metadata.pop("enabled")
    embedded_spark_metadata.pop("metadataPath")
    embedded_spark_metadata.pop("metadataSha256")
    if embedded_spark_metadata != spark_metadata:
        raise SystemExit("run manifest Spark metadata does not match spark-profile.json")
    raw_spark_profile = spark_profile_path.read_bytes()
    if embedded_spark.get("profile", {}).get("sha256") != hashlib.sha256(raw_spark_profile).hexdigest():
        raise SystemExit("run manifest Spark profile SHA mismatch")
PY

echo "Completed Phase 2 runtime sample $run_id"
