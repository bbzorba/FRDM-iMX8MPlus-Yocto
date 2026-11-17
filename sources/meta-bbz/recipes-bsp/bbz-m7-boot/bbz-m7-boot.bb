SUMMARY = "bbz-m7-boot: bare-metal bootloader for the i.MX 8M Plus Cortex-M7"
DESCRIPTION = "Started by U-Boot (bootaux) before Linux. Console on UART4, M7-only watchdog \
(WDOG3), A53 heartbeat supervision, protected DDR region check, application slot."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

inherit externalsrc deploy
EXTERNALSRC = "${BBZ_APPS_DIR}/m7_bootloader"
EXTERNALSRC_BUILD = "${WORKDIR}/build"

DEPENDS = "gcc-arm-none-eabi-native"
EXTRA_OEMAKE = "-C ${S} BUILD='${B}' CROSS=arm-none-eabi-"
# bare metal: none of the target CFLAGS/LDFLAGS apply
do_compile() {
    oe_runmake CFLAGS= LDFLAGS=
}

# ELF for Linux remoteproc, BIN for U-Boot bootaux (put on the boot partition by bbz-image)
do_install() {
    install -d ${D}${nonarch_base_libdir}/firmware
    arm-none-eabi-strip --strip-debug -o ${D}${nonarch_base_libdir}/firmware/bbz-m7-boot.elf ${B}/bbz-m7-boot.elf
}
do_deploy() {
    install -m 0644 ${B}/bbz-m7-boot.bin ${DEPLOYDIR}/bbz-m7-boot.bin
}
addtask deploy after do_compile

FILES:${PN} = "${nonarch_base_libdir}/firmware"
INSANE_SKIP:${PN} = "arch ldflags already-stripped"
PACKAGE_ARCH = "${MACHINE_ARCH}"
# Cortex-M7 (32-bit Arm) firmware, not target (AArch64) code: no Yocto strip/debug split
INHIBIT_PACKAGE_STRIP = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"
INHIBIT_SYSROOT_STRIP = "1"
