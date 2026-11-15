#!/bin/bash
# Waits until no BitBake is running, then runs 'bitbake -k <image>' in a clean environment.
# -k = keep going: if one recipe fails, everything not depending on it still builds.
# Output goes to scripts/auto-build.log; the exit code is appended at the end.
#
# Usage: scripts/auto-build.sh [image]        (default image: bbz-image)
#        build dir can be overridden with BUILD_DIR=build-xyz
SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
BSP="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="${BUILD_DIR:-build-wayland}"
IMAGE="${1:-bbz-image}"
LOG="$SCRIPT_DIR/auto-build.log"

# Match BitBake by its install path: a bare "bitbake-server" pattern also matches other
# processes that merely mention it in their arguments (e.g. earlyoom's --avoid list).
while pgrep -f '[b]itbake/bin/bitbake-server|[b]itbake/bin/bitbake ' >/dev/null; do sleep 60; done
echo "=== START $(date '+%F %T') image=$IMAGE build=$BUILD_DIR" >> "$LOG"
# env -i: start from an empty environment so nothing from ~/.bashrc (e.g. PetaLinux) leaks in
env -i HOME="$HOME" USER="$USER" LANG=C.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    BSP="$BSP" BUILD_DIR="$BUILD_DIR" IMAGE="$IMAGE" \
    bash -c 'cd "$BSP" && source setup-environment "$BUILD_DIR" >/dev/null && bitbake -k "$IMAGE"' >> "$LOG" 2>&1
rc=$?
echo "=== EXIT $rc $(date '+%F %T')" >> "$LOG"
exit $rc
