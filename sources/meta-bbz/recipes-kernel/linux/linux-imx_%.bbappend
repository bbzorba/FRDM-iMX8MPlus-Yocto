# Kernel release string (uname -r) without the git hash.
#
# With SCMVERSION = "y" (the default from fsl-kernel-localversion.bbclass) the kernel
# enables CONFIG_LOCALVERSION_AUTO, and scripts/setlocalversion appends "-g<hash>" and,
# if git reports local changes, "-dirty". Inside the build that git check can report a
# clean tree as dirty, so the kernel package became "kernel-6.18.37-...-dirty" while
# out-of-tree modules (jailhouse) depended on the name without "-dirty" -> do_rootfs failed.
#
# With SCMVERSION = "n" the release is just ${PV}${LOCALVERSION}, e.g. "6.18.37-2.1.0":
# deterministic, and every kernel module is built against the same name.
SCMVERSION = "n"

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
# Wi-Fi: cap the IW612 SDIO bus (usdhc1) at 100 MHz. The first SDIO transfers after boot
# time out less often (about 1 boot in 4 instead of 1 in 2); bbz-wifi-recover handles the rest.
SRC_URI += "file://0001-imx8mp-frdm-limit-wifi-sdio-to-100mhz.patch"
# Our device tree, imx8mp-bbz.dts: board DTS + Cortex-M7 + which core owns which peripheral
SRC_URI += "file://0002-arm64-dts-add-imx8mp-bbz.patch"
# Cortex-M7: the RPMsg TTY driver is not loaded on demand when
# M7 firmware announces its channel, so load it at boot; then /dev/ttyRPMSG* appears.
KERNEL_MODULE_AUTOLOAD += "imx_rpmsg_tty"
