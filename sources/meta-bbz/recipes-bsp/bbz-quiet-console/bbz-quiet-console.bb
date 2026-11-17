SUMMARY = "Show only kernel errors on the serial console"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://60-bbz-console.conf"
S = "${UNPACKDIR}"

inherit allarch

do_install() {
    install -D -m 0644 ${S}/60-bbz-console.conf ${D}${libdir}/sysctl.d/60-bbz-console.conf
}

FILES:${PN} = "${libdir}/sysctl.d"
