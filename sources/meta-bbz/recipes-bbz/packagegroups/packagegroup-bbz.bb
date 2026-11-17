# A packagegroup is an empty package whose only job is to RDEPEND on a list of
# other packages. Images then install the group instead of a long flat list.

SUMMARY = "Userspace tools for the bbz i.MX 8M Plus image"

inherit packagegroup

PACKAGES = " \
    ${PN}-network \
    ${PN}-devtools \
    ${PN}-apps \
"

# NetworkManager: daemon + CLI (nmcli) + text UI (nmtui) + Wi-Fi plugin.
RDEPENDS:${PN}-network = " \
    networkmanager \
    networkmanager-nmcli \
    networkmanager-nmtui \
    networkmanager-wifi \
    bbz-wifi-config \
    bbz-wifi-recover \
    wpa-supplicant \
    iw \
    ca-certificates \
"

# Things you'll want on a dev board.
#   npm:         current npm from the registry (meta-bbz/recipes-devtools/npm), replacing
#                the older npm bundled with nodejs (nodejs-npm)
#   claude-code: Claude Code CLI, prebuilt arm64 binary (meta-bbz/recipes-devtools/claude-code)
RDEPENDS:${PN}-devtools = " \
    git \
    htop \
    nano \
    vim \
    tmux \
    rsync \
    strace \
    gdbserver \
    i2c-tools \
    devmem2 \
    nodejs \
    npm \
    claude-code \
    bbz-claude-auth \
    bbz-flash-mode \
"

# Our own applications (sources in <repo>/apps/)
RDEPENDS:${PN}-apps = " \
    hello-bbz \
    home-hub-node \
"
