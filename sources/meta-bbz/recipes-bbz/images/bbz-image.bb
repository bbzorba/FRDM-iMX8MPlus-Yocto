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

# Extra free space in the rootfs, in KiB (here ~2 GiB), so you have room for apt/npm installs.
IMAGE_ROOTFS_EXTRA_SPACE = "2097152"

export IMAGE_BASENAME = "bbz-image"
