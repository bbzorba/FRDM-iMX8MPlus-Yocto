SUMMARY = "Claude Code: Anthropic's agentic coding CLI (prebuilt native binary)"
DESCRIPTION = "Installs the official linux-arm64 (glibc) Claude Code binary as /usr/bin/claude. \
It is the same binary that 'npm install -g @anthropic-ai/claude-code' selects on an arm64 board: \
the npm package is only a wrapper around this platform package."
HOMEPAGE = "https://docs.anthropic.com/en/docs/claude-code"

# Proprietary, under Anthropic's commercial terms (see LICENSE.md inside the package).
# The binary is downloaded at build time and never committed to this repository.
LICENSE = "CLOSED"

# To update: change the version in the file name, then set the new sha256 printed by
#   curl -sL https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64/-/claude-code-linux-arm64-<ver>.tgz | sha256sum
# Versions: https://registry.npmjs.org/@anthropic-ai/claude-code  ("latest" and "stable" dist-tags)
SRC_URI = "https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64/-/claude-code-linux-arm64-${PV}.tgz;subdir=${BP};downloadfilename=claude-code-linux-arm64-${PV}.tgz"
SRC_URI[sha256sum] = "f50a204ede2553b62a801bacab66d6812f4c82adf27b5fadf589ec7219459cef"

S = "${UNPACKDIR}/${BP}/package"

# Only an aarch64 glibc build is fetched here
COMPATIBLE_HOST = "aarch64.*-linux"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    install -D -m 0755 ${S}/claude ${D}${bindir}/claude

    # Updates come from rebuilding the image (new recipe version), so the CLI must not try
    # to replace a file owned by the package manager.
    install -d ${D}${sysconfdir}/profile.d
    echo 'export DISABLE_AUTOUPDATER=1' > ${D}${sysconfdir}/profile.d/claude-code.sh
}

FILES:${PN} += "${sysconfdir}/profile.d/claude-code.sh"

# The binary is a single-file executable: its JavaScript payload is appended to the ELF file.
# Stripping it or splitting out debug info would cut that payload off and break the program.
INHIBIT_PACKAGE_STRIP = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"
INHIBIT_SYSROOT_STRIP = "1"
INSANE_SKIP:${PN} += "already-stripped ldflags textrel"

# Not needed to start, but used by Claude Code at runtime (git operations, HTTPS)
RDEPENDS:${PN} += "git ca-certificates"
