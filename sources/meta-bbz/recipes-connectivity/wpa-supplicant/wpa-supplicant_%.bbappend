# Re-enable wpa_supplicant's D-Bus interface, which NetworkManager needs.
#
# meta-imx's wpa-supplicant bbappend builds NXP's fork (v2.11-M007) and copies NXP's own
# defconfig over the build config. That defconfig has CONFIG_CTRL_IFACE_DBUS_NEW commented
# out, so the binary has no '-u' option. The systemd unit (ExecStart=wpa_supplicant -u,
# Type=dbus) then exits right away. NetworkManager can't D-Bus-activate the supplicant, gives
# up, and every Wi-Fi interface (mlan0, uap0, wfd0) stays "unavailable": no Wi-Fi at all.
#
# This append runs after NXP's (meta-bbz has the higher layer priority), on the final .config.
do_configure:append() {
    sed -i -e '/CONFIG_CTRL_IFACE_DBUS_NEW=/d' -e '/CONFIG_CTRL_IFACE_DBUS_INTRO=/d' \
        ${S}/wpa_supplicant/.config
    echo 'CONFIG_CTRL_IFACE_DBUS_NEW=y' >> ${S}/wpa_supplicant/.config
    echo 'CONFIG_CTRL_IFACE_DBUS_INTRO=y' >> ${S}/wpa_supplicant/.config
}
