#!/bin/bash
# Logs PC health every 10 minutes while a Yocto build runs, to scripts/build-health.csv.
# Exits (after a final sample) when BitBake is no longer running.
# CPU power is read from Intel RAPL, which needs root: run with passwordless sudo, or the
# cpu_W column stays empty.
SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
BSP="$(dirname "$SCRIPT_DIR")"
LOG="$SCRIPT_DIR/build-health.csv"
B="$BSP/${BUILD_DIR:-build-wayland}"
RAPL=/sys/class/powercap/intel-rapl:0/energy_uj
INTERVAL=${INTERVAL:-600}

[ -s "$LOG" ] || echo "time,cpu_pkg_C,core_max_C,nvme_max_C,gpu_C,cpu_W,gpu_W,load1,disk_free_GB,swap_used_MB,pkg_throttle_count,task,bitbake,alerts" > "$LOG"

hw() { for h in /sys/class/hwmon/hwmon*; do [ "$(cat $h/name)" = "$1" ] && echo $h; done; }
CORE=$(hw coretemp)

while true; do
    e1=$(sudo -n cat $RAPL 2>/dev/null); t1=$(date +%s.%N); sleep 10; e2=$(sudo -n cat $RAPL 2>/dev/null); t2=$(date +%s.%N)
    cpu_w=""
    [ -n "$e1" ] && [ -n "$e2" ] && [ "$e2" -ge "$e1" ] && cpu_w=$(echo "scale=1; ($e2-$e1)/1000000/($t2-$t1)" | bc)

    pkg=$(( $(cat $CORE/temp1_input) / 1000 ))
    cmax=$(for f in $CORE/temp*_input; do echo $(( $(cat $f) / 1000 )); done | sort -n | tail -1)
    nvme=$(for h in $(hw nvme); do for f in $h/temp*_input; do echo $(( $(cat $f) / 1000 )); done; done | sort -n | tail -1)
    read gpu_c gpu_w < <(nvidia-smi --query-gpu=temperature.gpu,power.draw --format=csv,noheader,nounits 2>/dev/null | tr -d ',')
    load=$(cut -d' ' -f1 /proc/loadavg)
    free_gb=$(df -BG --output=avail "$BSP" | tail -1 | tr -dc 0-9)
    swap_mb=$(free -m | awk '/Swap/{print $3}')
    thr=$(cat /sys/devices/system/cpu/cpu0/thermal_throttle/package_throttle_count)
    clog=$(ls -t "$B"/tmp/log/cooker/*/[0-9]*.log 2>/dev/null | head -1)
    task=$(grep -oE "Running task [0-9]+ of [0-9]+" "$clog" 2>/dev/null | tail -1 | grep -oE "[0-9]+ of [0-9]+" | tr ' ' '_')
    if pgrep -f "[b]itbake/bin/bitbake-server" >/dev/null; then bb=running
    elif grep -q "Summary: There w" "$clog" 2>/dev/null; then bb=FAILED
    else bb=stopped; fi

    alerts=""
    [ "$cmax" -ge 99 ] && alerts+="CPU_AT_LIMIT "
    [ -n "$nvme" ] && [ "$nvme" -ge 78 ] && alerts+="NVME_HOT "
    [ "$free_gb" -lt 20 ] && alerts+="DISK_LOW "
    [ "$(free -m | awk '/Swap/{print $4}')" -lt 300 ] && alerts+="SWAP_FULL "
    [ "$bb" != running ] && alerts+="BUILD_$bb "

    echo "$(date +%F\ %T),$pkg,$cmax,$nvme,$gpu_c,$cpu_w,$gpu_w,$load,$free_gb,$swap_mb,$thr,$task,$bb,${alerts% }" >> "$LOG"
    [ "$bb" != running ] && exit 0
    sleep $((INTERVAL - 10))
done
