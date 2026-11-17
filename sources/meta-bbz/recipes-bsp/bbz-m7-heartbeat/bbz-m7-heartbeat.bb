SUMMARY = "Linux heartbeat for the Cortex-M7 supervisor (bbz-m7-boot)"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"
SRC_URI = "file://bbz-m7-heartbeat file://bbz-m7-heartbeat.service"
S = "${UNPACKDIR}"
inherit allarch systemd
SYSTEMD_SERVICE:${PN} = "bbz-m7-heartbeat.service"
do_install() {
    install -D -m 0755 ${S}/bbz-m7-heartbeat ${D}${bindir}/bbz-m7-heartbeat
    install -D -m 0644 ${S}/bbz-m7-heartbeat.service ${D}${systemd_system_unitdir}/bbz-m7-heartbeat.service
}
RDEPENDS:${PN} = "python3-core python3-mmap"
