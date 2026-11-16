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

# Board fix: cap the IW612 Wi-Fi SDIO bus (usdhc1) at 100 MHz. At SDR104/200 MHz the first
# SDIO transfer after NetworkManager starts timed out in about half of the boots and Wi-Fi
# stayed dead until reboot; at 100 MHz all test boots were clean.
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
SRC_URI += "file://0001-imx8mp-frdm-limit-wifi-sdio-to-100mhz.patch"
