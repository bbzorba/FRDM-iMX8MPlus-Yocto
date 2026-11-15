# Custom image for i.MX 8M Plus.
#
# We build on NXP's imx-image-core (Weston/Wayland desktop, GPU/VPU stack,
# ssh, package management) and add our own pieces on top.
# 'require' pulls the other recipe's text in as if it were written here.

require recipes-fsl/images/imx-image-core.bb

SUMMARY = "bbz custom image: NXP core + NetworkManager + apt"

# IMAGE_FEATURES are high-level switches handled by core-image.bbclass.
#   package-management -> keeps the package DB + apt/dpkg in the rootfs
#                         (PACKAGE_CLASSES = "package_deb" is set in local.conf)
IMAGE_FEATURES += "package-management ssh-server-openssh"

IMAGE_INSTALL += " \
    packagegroup-bbz-network \
    packagegroup-bbz-devtools \
    packagegroup-bbz-apps \
    apt \
    dpkg \
"

# NetworkManager and ConnMan both want to own the interfaces; keep only NM.
# BAD_RECOMMENDATIONS drops packages that are pulled in only as recommendations.
IMAGE_INSTALL:remove = "connman connman-client connman-tools"
BAD_RECOMMENDATIONS += "connman connman-client connman-tools"

# One network manager only: NetworkManager. NXP's image also enables systemd-networkd
# (systemd-conf ships 69-systemd-conf-imx.network for eth*, plus a drop-in running
# systemd-networkd-wait-online --any). networkd then competes with NetworkManager for eth0/eth1,
# and without an Ethernet cable wait-online holds network-online.target for up to 2 minutes:
# every service ordered after it (home-hub-node, nxp-bt-enable) starts minutes late.
# Masking = a /dev/null symlink in /etc/systemd/system, which wins over any enable link.
bbz_mask_networkd() {
    for unit in systemd-networkd.service systemd-networkd.socket \
                systemd-networkd-varlink.socket systemd-networkd-resolve-hook.socket \
                systemd-networkd-wait-online.service systemd-networkd-persistent-storage.service; do
        ln -sf /dev/null ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/$unit
    done
}
ROOTFS_POSTPROCESS_COMMAND += "bbz_mask_networkd"

# Extra free space in the rootfs, in KiB (here ~2 GiB), so you have room for apt/npm installs.
IMAGE_ROOTFS_EXTRA_SPACE = "2097152"

export IMAGE_BASENAME = "bbz-image"
