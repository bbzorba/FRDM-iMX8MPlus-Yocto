#!/bin/bash
# Compact status of a running (or the last) BitBake build, meant to be run every few minutes.
# Prints: state + progress, tasks running now, temperatures/RAM/swap/disk,
# and only the ERROR lines that are new since the previous run.
SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
BSP="$(dirname "$SCRIPT_DIR")"
B="$BSP/${BUILD_DIR:-build-wayland}"
STATE="$SCRIPT_DIR/.check-build.state"
clog=$(ls -t "$B"/tmp/log/cooker/*/[0-9]*.log 2>/dev/null | head -1)

# [x]yz patterns stop pgrep from matching its own command line
if pgrep -f '[b]itbake/bin/bitbake-server' >/dev/null; then bb=RUNNING; else bb=STOPPED; fi
prog=$(grep -oE "Running (noexec |setscene )?task [0-9]+ of [0-9]+" "$clog" 2>/dev/null | tail -1 | grep -oE "[0-9]+ of [0-9]+")
tasks=$(ps -eo etimes=,args= | awk '/run\.do_[a-z_]+\.[0-9]+/ {match($0,/work\/[^/]+\/[^/]+/); r=substr($0,RSTART+5,RLENGTH-5); sub(/.*\//,"",r); match($0,/run\.do_[a-z_]+/); t=substr($0,RSTART+4,RLENGTH-4); printf "%s:%s(%dm) ", r, t, $1/60}')
core=$(cat /sys/class/hwmon/hwmon*/temp*_input 2>/dev/null | sort -n | tail -1)
pkg=$(for h in /sys/class/hwmon/hwmon*; do [ "$(cat $h/name)" = coretemp ] && cat $h/temp1_input; done)
nvme=$(for h in /sys/class/hwmon/hwmon*; do [ "$(cat $h/name)" = nvme ] && cat $h/temp*_input; done | sort -n | tail -1)
read memavail swapused < <(free -m | awk '/Mem/{a=$7} /Swap/{s=$3} END{print a, s}')
disk=$(df -BG --output=avail "$BSP" | tail -1 | tr -dc 0-9)
runner=$(pgrep -f '[a]uto-build\.sh' >/dev/null && echo alive || echo none)
guard=$(pgrep -f '[m]em-guard\.sh' >/dev/null && echo alive || echo none)
kills=$(grep -c KILL "$SCRIPT_DIR/mem-guard.log" 2>/dev/null)
last=$(grep -E "^=== (START|EXIT)" "$SCRIPT_DIR/auto-build.log" 2>/dev/null | tail -1)

echo "$(date +%T) bitbake=$bb runner=$runner guard=$guard guard_kills=${kills:-0} progress=${prog:-?} log=$(basename "${clog:-none}")"
echo "running: ${tasks:-none}"
echo "cpu_pkg=$((${pkg:-0}/1000))C max=$((${core:-0}/1000))C nvme=$((${nvme:-0}/1000))C mem_avail=${memavail}MB swap=${swapused}MB disk=${disk}G  ${last}"

# New ERROR / failed-task lines since last check (keyed on log file + line count)
[ -n "$clog" ] || exit 0
read slog sline 2>/dev/null < "$STATE"
[ "$slog" = "$clog" ] || sline=0
total=$(wc -l < "$clog")
new=$(tail -n +$((sline + 1)) "$clog" | grep -E "^ERROR:|^Summary:|failed with exit code" | grep -v "^ERROR: Logfile" | cut -c1-220)
[ -n "$new" ] && { echo "NEW ERRORS:"; echo "$new" | tail -8; }
echo "$clog $total" > "$STATE"
