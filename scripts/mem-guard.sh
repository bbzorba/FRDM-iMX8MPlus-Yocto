#!/bin/bash
# Last-resort RAM guard for unattended Yocto builds.
# Every 5 s it reads /proc/meminfo (near-zero CPU). If RAM is about to run out, it kills
# the largest build process (a compiler/linker under <build>/tmp/work) so that one
# BitBake task fails instead of the whole PC freezing in swap. BitBake (-k) carries on
# and the failed task is simply retried on the next build.
#
# Run it in the background for the duration of a build, and stop it afterwards:
#   nice -n 19 scripts/mem-guard.sh &      ...      kill %1
SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
BSP="$(dirname "$SCRIPT_DIR")"
LOG="$SCRIPT_DIR/mem-guard.log"
WORK="$BSP/${BUILD_DIR:-build-wayland}/tmp/work"
CRIT_MB=1200          # kill if MemAvailable below this...
LOW_MB=2500           # ...or below this while swap is almost full
SWAP_LOW_MB=500

echo "$(date '+%F %T') guard started (pid $$)" >> "$LOG"
trap 'echo "$(date "+%F %T") guard stopped (pid $$)" >> "$LOG"; exit 0' TERM INT
while true; do
    read avail swapfree < <(awk '/^MemAvailable/{a=$2} /^SwapFree/{s=$2} END{print int(a/1024), int(s/1024)}' /proc/meminfo)
    if [ "$avail" -lt "$CRIT_MB" ] || { [ "$avail" -lt "$LOW_MB" ] && [ "$swapfree" -lt "$SWAP_LOW_MB" ]; }; then
        # biggest process whose command line points into the Yocto work tree, never bitbake itself
        victim=$(ps -eo pid=,rss=,args= --sort=-rss | grep -F "$WORK" | grep -vE 'bitbake|grep' | head -1)
        [ -z "$victim" ] && victim=$(ps -eo pid=,rss=,comm= --sort=-rss | grep -E ' (cc1plus|cc1|ld|ld\.bfd|ld\.gold|ld\.lld|lld|rustc|node|clang|clang\+\+)$' | head -1)
        if [ -n "$victim" ]; then
            pid=$(echo "$victim" | awk '{print $1}')
            kill -9 "$pid" 2>/dev/null
            echo "$(date '+%F %T') KILL avail=${avail}MB swapfree=${swapfree}MB -> $(echo "$victim" | cut -c1-200)" >> "$LOG"
            sleep 15 & wait $!
            continue
        fi
    fi
    # 'sleep & wait' lets the TERM trap run immediately instead of after the sleep
    sleep 5 & wait $!
done
