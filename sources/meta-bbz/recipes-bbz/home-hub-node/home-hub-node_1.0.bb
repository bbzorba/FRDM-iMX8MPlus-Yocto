SUMMARY = "Home Hub client node: reports the board's temperatures and metrics to the ESP32-C6 Home Hub"
DESCRIPTION = "Reads thermal zones, USB-C PD hwmon, CPU load/frequency, memory, disk and uptime \
through sysfs/procfs and POSTs them as JSON to http://<HUB_HOST>/api/node every 10 s. \
Started at boot by systemd. Source: apps/home_hub_node in this repository."
HOMEPAGE = "https://github.com/bbzorba/FRDM-iMX8MPlus-Yocto"
LICENSE = "CLOSED"

# The source is not fetched: it lives in this repository (apps/home_hub_node, see
# BBZ_APPS_DIR in conf/layer.conf). externalsrc builds it in place, and BitBake checksums
# the files there, so any change to the source triggers a rebuild on the next bitbake run.
inherit externalsrc systemd
EXTERNALSRC = "${BBZ_APPS_DIR}/home_hub_node"
# Build output goes to the work dir, not into the source tree
EXTERNALSRC_BUILD = "${WORKDIR}/build"
# Don't create oe-workdir / oe-logs symlinks inside apps/home_hub_node
EXTERNALSRC_SYMLINKS = ""

# The app's own Makefile is reused: Yocto's cross compiler, flags (incl. hardening and
# GNU_HASH LDFLAGS) and an out-of-tree build directory are passed in.
EXTRA_OEMAKE = "-C ${S} BUILD='${B}' CC='${CC}' CFLAGS='${CFLAGS}' LDFLAGS='${LDFLAGS}'"

do_configure[noexec] = "1"

do_compile() {
    oe_runmake
}

do_install() {
    install -D -m 0755 ${B}/home_hub_node ${D}${bindir}/home_hub_node
    install -D -m 0644 ${S}/home-hub-node.service ${D}${systemd_system_unitdir}/home-hub-node.service
}

# Enabled at boot (systemctl enable happens at image build time)
SYSTEMD_SERVICE:${PN} = "home-hub-node.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"
