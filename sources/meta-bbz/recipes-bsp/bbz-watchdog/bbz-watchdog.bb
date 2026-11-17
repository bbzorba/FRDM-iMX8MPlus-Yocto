SUMMARY = "Arm the i.MX 8M Plus hardware watchdog (WDOG1) from systemd"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://10-bbz-watchdog.conf"
S = "${UNPACKDIR}"

inherit allarch

do_install() {
    install -D -m 0644 ${S}/10-bbz-watchdog.conf ${D}${systemd_unitdir}/system.conf.d/10-bbz-watchdog.conf
}

FILES:${PN} = "${systemd_unitdir}/system.conf.d"
RDEPENDS:${PN} = "systemd"
