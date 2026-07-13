#!/usr/bin/env bash
set -euo pipefail

# Runs one restart-isolated Paper + real TCP client sample. A workflow must
# invoke this script sequentially in the desired ABBA order; it deliberately
# owns only one JVM/run so cleanup failures cannot leak state into the next one.

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

[[ "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
  || { echo "PHASE2_RUN_ID contains unsafe characters" >&2; exit 64; }

case "$variant" in
  A|B) ;;
  *) echo "PHASE2_VARIANT must be A or B" >&2; exit 64 ;;
esac
case "$scenario" in
  static-steady|static-spawn|visibility-return) ;;
  *) echo "Unsupported PHASE2_SCENARIO: $scenario" >&2; exit 64 ;;
esac
for value in "$server_port" "$item_count" "$warmup_seconds" "$settle_seconds" \
  "$measure_seconds" "$capture_snaplen"; do
  [[ "$value" =~ ^[0-9]+$ ]] || { echo "Numeric input is invalid: $value" >&2; exit 64; }
done
(( server_port >= 1 && server_port <= 65535 )) \
  || { echo "PHASE2_SERVER_PORT is outside 1..65535" >&2; exit 64; }
(( item_count >= 1 && item_count <= 8192 )) \
  || { echo "PHASE2_ITEM_COUNT is outside 1..8192" >&2; exit 64; }
(( warmup_seconds >= 10 && settle_seconds >= 5 && measure_seconds >= 5 )) \
  || { echo "Warmup/settle/measure windows are too short" >&2; exit 64; }
(( warmup_seconds + settle_seconds + measure_seconds + 120 <= 600 )) \
  || { echo "Requested window exceeds the benchmark scene lifetime cap" >&2; exit 64; }
if [[ "$scenario" == visibility-return ]] && (( measure_seconds < 10 )); then
  echo "visibility-return requires at least a 10-second fixed window" >&2
  exit 64
fi
[[ "$capture_enabled" == 0 || "$capture_enabled" == 1 ]] \
  || { echo "PHASE2_CAPTURE_ENABLED must be 0 or 1" >&2; exit 64; }
[[ -f "$plugin_jar" && -f "$paper_jar" ]] \
  || { echo "Plugin or Paper JAR is missing" >&2; exit 66; }
[[ -f "$client_root/client-build-manifest.json" ]] \
  || { echo "Prepared protocol client manifest is missing" >&2; exit 66; }
[[ -d "$client_root/node-minecraft-protocol" ]] \
  || { echo "Prepared node-minecraft-protocol directory is missing" >&2; exit 66; }

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

packet_only = scenario == "visibility-return" or variant == "B"
visibility_limit = scenario == "visibility-return" and variant == "B"
replace_once("      PacketOnlyStatic: false",
             f"      PacketOnlyStatic: {str(packet_only).lower()}")
replace_once("      Enabled: false\n      BucketSize: 128\n      RestorePerTick: 32",
             f"      Enabled: {str(visibility_limit).lower()}\n"
             "      BucketSize: 128\n      RestorePerTick: 32")
replace_once("      EventDriven: false", "      EventDriven: false")
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

mkfifo "$console_fifo"
exec 3<>"$console_fifo"
console_open=1
(
  cd "$run_directory"
  exec java -Xms2G -Xmx2G -XX:+UseG1GC -XX:+AlwaysPreTouch \
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
send_console "gamerule minecraft:do_mob_spawning false"
send_console "gamerule minecraft:do_weather_cycle false"
send_console "gamerule minecraft:do_daylight_cycle false"
send_console "gamerule minecraft:random_tick_speed 0"
send_console "gamerule minecraft:spawn_radius 0"
send_console "setworldspawn 0 -60 0"
send_console "weather clear"
send_console "time set day"
sleep 1
if grep -Fq -- "Incorrect argument for command" "$server_log"; then
  echo "Paper rejected one of the deterministic-world commands" >&2
  exit 1
fi

PHASE2_MC_PROTOCOL_MODULE="$client_root/node-minecraft-protocol" \
PHASE2_SERVER_HOST=127.0.0.1 \
PHASE2_SERVER_PORT="$server_port" \
PHASE2_CLIENT_USERNAME=IVBench \
PHASE2_CLIENT_VERSION=26.1.2 \
PHASE2_CLIENT_READY_FILE="$client_ready" \
PHASE2_CLIENT_STATE_FILE="$client_state" \
node "$(dirname "$0")/phase2-protocol-client.js" > "$client_log" 2>&1 &
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

send_console "iv perf scene static $item_count $lifetime_ticks IVBench"
wait_for_log "Spawned $item_count static benchmark items" 60
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

if [[ "$scenario" == visibility-return ]]; then
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
  spawn_count="$(grep -Fc -- "Spawned $item_count static benchmark items" "$server_log" 2>/dev/null || true)"
  send_console "iv perf scene static $item_count $lifetime_ticks IVBench"
  wait_for_log_count "Spawned $item_count static benchmark items" "$((spawn_count + 1))" 60
elif [[ "$scenario" == visibility-return ]]; then
  teleport_count="$(grep -Fc -- "Teleported IVBench" "$server_log" 2>/dev/null || true)"
  send_console "tp IVBench $initial_x $initial_y $initial_z"
  wait_for_log_count "Teleported IVBench" "$((teleport_count + 1))" 30
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
  pwsh -NoProfile -File "$(dirname "$0")/analyze-phase2-pcap.ps1" "$capture_path" \
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
import json, sys
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
expected_packet_only = scenario == "visibility-return" or variant == "B"
expected_limiter = scenario == "visibility-return" and variant == "B"
if metrics.get("packetOnlyStatic") is not expected_packet_only:
    raise SystemExit(f"packetOnlyStatic does not match {scenario}/{variant}")
if metrics.get("visibilityRateLimit") is not expected_limiter:
    raise SystemExit(f"visibilityRateLimit does not match {scenario}/{variant}")
if scenario == "static-spawn":
    if variant == "B":
        if metrics.get("packetOnlyItemSyncs") != item_count or metrics.get("bukkitEntitySpawns") != 0:
            raise SystemExit("candidate did not exercise the pure packet-only spawn path")
    elif metrics.get("packetOnlyItemSyncs") != 0 or metrics.get("bukkitEntitySpawns") != item_count:
        raise SystemExit("baseline did not exercise one Bukkit tracker anchor per logical item")
if scenario == "visibility-return":
    if metrics.get("virtualSpawnBundles") != item_count:
        raise SystemExit("visibility return did not respawn every virtual item within the window")
    queued = metrics.get("visibilityShowsQueued")
    drained = metrics.get("visibilityShowsDrained")
    if variant == "B" and (queued != item_count or drained != item_count):
        raise SystemExit(f"limiter did not queue/drain every item: queued={queued} drained={drained}")
    if variant == "A" and (queued != 0 or drained != 0):
        raise SystemExit(f"unlimited baseline unexpectedly used the visibility queue: queued={queued} drained={drained}")
with open(output_path, "w", encoding="utf-8", newline="\n") as stream:
    json.dump(metrics, stream, ensure_ascii=False, indent=2)
    stream.write("\n")
PY

cat > "$run_directory/run-manifest.json" <<EOF
{
  "schemaVersion": 1,
  "runId": "$run_id",
  "scenario": "$scenario",
  "variant": "$variant",
  "captureEnabled": $([[ "$capture_enabled" == 1 ]] && echo true || echo false),
  "captureSnaplen": $capture_snaplen,
  "serverPort": $server_port,
  "itemCount": $item_count,
  "warmupSeconds": $warmup_seconds,
  "settleSeconds": $settle_seconds,
  "measureSeconds": $measure_seconds,
  "windowStartEpochSeconds": $window_start,
  "windowEndEpochSeconds": $window_end,
  "pluginSha256": "$plugin_sha",
  "paperSha256": "$paper_sha",
  "clientManifestSha256": "$client_manifest_sha",
  "configSha256": "$config_sha"
}
EOF

send_console "iv perf clear IVBench"
sleep 2
kill -TERM "$client_pid"
wait "$client_pid"
client_pid=""
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

echo "Completed Phase 2 runtime sample $run_id"
