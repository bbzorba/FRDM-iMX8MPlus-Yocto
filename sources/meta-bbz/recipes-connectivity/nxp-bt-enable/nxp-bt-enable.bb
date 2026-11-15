SUMMARY = "Load the NXP IW612 Bluetooth driver at boot, after the Wi-Fi firmware"
DESCRIPTION = "NXP blacklists btnxpuart so it doesn't race the moal Wi-Fi driver, which downloads \
the combined Wi-Fi + Bluetooth firmware. Nothing loads it afterwards, so hci0 never appears. \
This systemd unit waits for mlan0 and then loads btnxpuart."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://nxp-bt-enable.service"
S = "${UNPACKDIR}"

inherit allarch systemd

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    install -D -m 0644 ${S}/nxp-bt-enable.service ${D}${systemd_system_unitdir}/nxp-bt-enable.service
}

SYSTEMD_SERVICE:${PN} = "nxp-bt-enable.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"
RDEPENDS:${PN} = "kmod bluez5"
