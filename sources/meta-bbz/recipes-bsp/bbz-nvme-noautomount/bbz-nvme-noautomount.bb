SUMMARY = "Don't auto-mount the NVMe SSD at boot"
DESCRIPTION = "On this board, fsck + mount of the M.2 NVMe SSD at the same time as the rest \
of the boot browned the board out: a power-on reset 6-10 s into the kernel, often several \
boots in a row. udev's automount (udev-extraconf) skips devices listed in \
/etc/udev/mount.ignorelist.d; mount the SSD by hand once the system is up."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://bbz-nvme"
S = "${UNPACKDIR}"

inherit allarch

do_install() {
    install -D -m 0644 ${S}/bbz-nvme ${D}${sysconfdir}/udev/mount.ignorelist.d/bbz-nvme
}

FILES:${PN} = "${sysconfdir}/udev/mount.ignorelist.d"
RDEPENDS:${PN} = "udev-extraconf"
