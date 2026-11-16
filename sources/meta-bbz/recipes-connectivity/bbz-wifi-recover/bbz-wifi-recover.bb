SUMMARY = "Recover the IW612 Wi-Fi when its SDIO link failed at boot"
DESCRIPTION = "Once per boot: if the kernel log shows the IW612 SDIO link failed (cmd53 write \
error, Host to Card Failed, Wakeup card timeout), stop NetworkManager and Bluetooth, reload the \
moal/mlan driver (which reloads the firmware), and start them again. Up to 3 attempts."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://bbz-wifi-recover file://bbz-wifi-recover.service"
S = "${UNPACKDIR}"

inherit allarch systemd

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    install -D -m 0755 ${S}/bbz-wifi-recover ${D}${sbindir}/bbz-wifi-recover
    install -D -m 0644 ${S}/bbz-wifi-recover.service ${D}${systemd_system_unitdir}/bbz-wifi-recover.service
}

SYSTEMD_SERVICE:${PN} = "bbz-wifi-recover.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"
RDEPENDS:${PN} = "kmod networkmanager-nmcli util-linux-logger"
