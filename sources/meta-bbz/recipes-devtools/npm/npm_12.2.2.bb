SUMMARY = "npm, the Node.js package manager (current release from the npm registry)"
DESCRIPTION = "Installs the npm release published on registry.npmjs.org in place of the older \
npm bundled with the nodejs recipe (package nodejs-npm), without rebuilding Node.js itself. \
npm is pure JavaScript and ships its dependencies inside its own tarball."
HOMEPAGE = "https://github.com/npm/cli"

# npm itself is Artistic-2.0; the bundled node_modules add the others
# (counted from their package.json files for 12.2.2).
LICENSE = "Artistic-2.0 & ISC & MIT & BlueOak-1.0.0 & Apache-2.0 & BSD-2-Clause & BSD-3-Clause & CC0-1.0 & CC-BY-3.0"
LIC_FILES_CHKSUM = "file://LICENSE;md5=55a3c686daad7dc7b9be707d72b6f3f4"

# To update: change the version in the file name, then set the new sha256 printed by
#   curl -sL https://registry.npmjs.org/npm/-/npm-<ver>.tgz | sha256sum
# npm 12 needs Node.js ^22.22.2 || ^24.15.0 || >=26 (our nodejs is 22.23.2).
SRC_URI = "https://registry.npmjs.org/npm/-/npm-${PV}.tgz;subdir=${BP}"
SRC_URI[sha256sum] = "07fecbf753e2c3bc10ebeb3e9ed8738f6a0cc1292998b79e2e9f1dd2a5525e0c"

# npm tarballs unpack into a "package/" directory
S = "${UNPACKDIR}/${BP}/package"

inherit allarch

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    install -d ${D}${nonarch_libdir}/node_modules/npm ${D}${bindir}
    cp -R --no-preserve=ownership ${S}/. ${D}${nonarch_libdir}/node_modules/npm/
    # Same layout as Node.js's own install: bin/npm -> lib/node_modules/npm/bin/npm-cli.js
    ln -sf ../lib/node_modules/npm/bin/npm-cli.js ${D}${bindir}/npm
    ln -sf ../lib/node_modules/npm/bin/npx-cli.js ${D}${bindir}/npx
}

FILES:${PN} = "${nonarch_libdir}/node_modules/npm ${bindir}/npm ${bindir}/npx"

# Same runtime deps as nodejs-npm (python3 is for node-gyp, which builds native addons)
RDEPENDS:${PN} = "nodejs bash python3-core python3-shell python3-datetime python3-misc python3-multiprocessing"

# Both packages own /usr/bin/npm and /usr/lib/node_modules/npm: never install them together.
RCONFLICTS:${PN} = "nodejs-npm"
RREPLACES:${PN} = "nodejs-npm"
