# Extra U-Boot options for this board (merged into NXP's imx8mp_frdm_defconfig by the
# config-fragment support of oe-core's u-boot-configure.inc).
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
SRC_URI += "file://led-blink.cfg"
