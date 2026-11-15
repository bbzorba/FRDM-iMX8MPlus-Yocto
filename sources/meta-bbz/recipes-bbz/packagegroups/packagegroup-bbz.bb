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
    wpa-supplicant \
    iw \
    ca-certificates \
"

# Things you'll want on a dev board. nodejs+npm make Claude Code installable
# on the target later (see README).
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
    nodejs-npm \
"

RDEPENDS:${PN}-apps = " \
    hello-bbz \
"
