# SiP calls to protect and lock the Cortex-M7 (used by U-Boot, see u-boot-imx_%.bbappend)
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
SRC_URI += "file://0001-imx8mp-bbz-SiP-calls-to-protect-and-lock-the-Cortex-.patch"
