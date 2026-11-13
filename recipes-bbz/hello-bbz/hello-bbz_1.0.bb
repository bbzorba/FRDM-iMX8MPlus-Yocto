# Minimal "from-scratch" recipe: a single C file compiled with the cross toolchain.
# File name convention: <PN>_<PV>.bb  ->  PN = hello-bbz, PV = 1.0

SUMMARY = "Hello world example for learning Yocto recipes"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

# file:// URIs are searched in FILESPATH, which includes ./files/ next to this recipe.
SRC_URI = "file://hello-bbz.c"

# Since wrynose, local files are unpacked into ${UNPACKDIR}; build from there.
S = "${UNPACKDIR}"

# do_compile runs inside the build sysroot with the cross compiler in ${CC}.
# ${CFLAGS}/${LDFLAGS} carry the target flags (and the --sysroot); always use them.
do_compile() {
    ${CC} ${CFLAGS} ${LDFLAGS} ${S}/hello-bbz.c -o hello-bbz
}

# do_install copies results into ${D} (a fake root). Packaging later splits ${D}
# into packages (hello-bbz, hello-bbz-dbg, ...) according to FILES:<pkg>.
do_install() {
    install -d ${D}${bindir}
    install -m 0755 hello-bbz ${D}${bindir}/hello-bbz
}
