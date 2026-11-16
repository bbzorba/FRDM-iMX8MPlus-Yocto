SUMMARY = "Reboot the board into U-Boot fastboot from Linux (for flashing over USB without the boot switch)"
DESCRIPTION = "bbz-flash-mode copies a one-time U-Boot script to the eMMC boot partition and \
reboots. U-Boot's bootflow scan runs it before the normal boot: the script deletes itself and \
starts 'fastboot usb 0', so the PC can flash over USB-C J3 with only SSH as control channel."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://flash-mode.cmd file://bbz-flash-mode"
S = "${UNPACKDIR}"

# mkimage wraps the plain-text command list into the script image U-Boot executes
DEPENDS = "u-boot-tools-native"
inherit allarch

do_configure[noexec] = "1"

do_compile() {
    mkimage -A arm64 -O linux -T script -C none -n "bbz flash mode" \
        -d ${S}/flash-mode.cmd ${B}/flash-mode.scr
}

do_install() {
    install -D -m 0644 ${B}/flash-mode.scr ${D}${datadir}/bbz-flash-mode/flash-mode.scr
    install -D -m 0755 ${S}/bbz-flash-mode ${D}${sbindir}/bbz-flash-mode
}

FILES:${PN} = "${datadir}/bbz-flash-mode ${sbindir}/bbz-flash-mode"
RDEPENDS:${PN} = "util-linux-findmnt util-linux-mount"
