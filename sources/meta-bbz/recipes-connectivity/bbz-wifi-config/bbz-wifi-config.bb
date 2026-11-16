SUMMARY = "NetworkManager Wi-Fi profile that auto-connects at boot"
DESCRIPTION = "Installs /etc/NetworkManager/system-connections/bbz-wifi.nmconnection for a \
WPA2/WPA3-Personal network. The SSID and passphrase are NOT part of this layer: they come \
from build-wayland/conf/site.conf, which is git-ignored. Without them the package is empty."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

# Set these in build-wayland/conf/site.conf (never in a tracked file):
#   BBZ_WIFI_SSID = "MyNetwork"
#   BBZ_WIFI_PSK  = "my passphrase"
BBZ_WIFI_SSID ??= ""
BBZ_WIFI_PSK ??= ""

# Rebuild when the credentials change, without writing the passphrase itself into BitBake's
# signature files (tmp/stamps, sstate *.siginfo): only a SHA-256 of it is used there.
BBZ_WIFI_PSK[vardepvalue] = "${@__import__('hashlib').sha256(d.getVar('BBZ_WIFI_PSK').encode()).hexdigest()}"
do_install[vardeps] += "BBZ_WIFI_SSID BBZ_WIFI_PSK"

inherit allarch

do_configure[noexec] = "1"
do_compile[noexec] = "1"

python do_install() {
    import hashlib, os, uuid

    # NetworkManager settings for NXP's IW612 driver (moal), installed even without credentials.
    # The driver creates three interfaces on one radio: mlan0 (station), uap0 (access point)
    # and wfd0 (Wi-Fi Direct). Left alone, NetworkManager manages all three: it activated the
    # profile on wfd0 instead of mlan0, reconfigured uap0, and at boot the radio's SDIO link
    # crashed ("cmd53 write error"). It also set random MAC addresses for scanning. Only mlan0
    # is managed, with the real MAC.
    confdir = d.expand('${D}${sysconfdir}/NetworkManager/conf.d')
    os.makedirs(confdir, exist_ok=True)
    with open(os.path.join(confdir, '90-nxp-iw612.conf'), 'w') as f:
        f.write("[keyfile]\n"
                "unmanaged-devices=interface-name:uap0;interface-name:wfd0;interface-name:p2p-dev-*\n"
                "\n"
                "[device-nxp-iw612]\n"
                "match-device=interface-name:mlan0\n"
                "wifi.scan-rand-mac-address=no\n")
    # Note: turning the chip's power save off (moal auto_ds=2 ps_mode=2) was tested and made
    # boots WORSE (failed 2 of 2 at 100 MHz, vs 0 of 6 with the driver default), so it is not set.

    # Give the IW612 firmware time to settle before NetworkManager touches mlan0. When NM
    # started ~2 s after the firmware download (its normal boot timing), the radio's SDIO link
    # timed out in 3 of 4 boots ("cmd53 write error=-110", failed in-band reset), and Wi-Fi was
    # dead until the next boot. Starting NM 8 s after mlan0 appears avoids it.
    dropin = d.expand('${D}${sysconfdir}/systemd/system/NetworkManager.service.d')
    os.makedirs(dropin, exist_ok=True)
    with open(os.path.join(dropin, '10-wait-nxp-wifi.conf'), 'w') as f:
        f.write("[Service]\n"
                "ExecStartPre=/bin/sh -c 'for i in $(seq 1 30); do "
                "[ -e /sys/class/net/mlan0 ] && break; sleep 1; done; sleep 8'\n")

    ssid = d.getVar('BBZ_WIFI_SSID') or ""
    psk = d.getVar('BBZ_WIFI_PSK') or ""
    if not ssid:
        bb.note("BBZ_WIFI_SSID is not set: no Wi-Fi profile installed")
        return
    if not psk:
        bb.warn("BBZ_WIFI_SSID is set but BBZ_WIFI_PSK is empty: no Wi-Fi profile installed")
        return
    if not 8 <= len(psk) <= 63:
        bb.fatal("BBZ_WIFI_PSK must be a WPA passphrase of 8 to 63 characters")

    # Store the 256-bit WPA key (PBKDF2-HMAC-SHA1 of passphrase + SSID, as wpa_passphrase does)
    # instead of the passphrase: it joins this network the same way, but the readable
    # passphrase, which may be reused elsewhere, is not in the image.
    key = hashlib.pbkdf2_hmac('sha1', psk.encode(), ssid.encode(), 4096, 32).hex()
    conn_uuid = uuid.uuid5(uuid.NAMESPACE_DNS, 'bbz-wifi.' + ssid)

    destdir = d.expand('${D}${sysconfdir}/NetworkManager/system-connections')
    os.makedirs(destdir, exist_ok=True)
    path = os.path.join(destdir, 'bbz-wifi.nmconnection')
    with open(path, 'w') as f:
        f.write(f"""[connection]
id=bbz-wifi
uuid={conn_uuid}
type=wifi
interface-name=mlan0
autoconnect=true

[wifi]
mode=infrastructure
ssid={ssid}

[wifi-security]
key-mgmt=wpa-psk
psk={key}

[ipv4]
method=auto

[ipv6]
method=auto
""")
    # NetworkManager ignores profiles that are readable by anyone but root
    os.chmod(path, 0o600)
}

FILES:${PN} = "${sysconfdir}/NetworkManager/system-connections ${sysconfdir}/NetworkManager/conf.d \
                ${sysconfdir}/systemd/system/NetworkManager.service.d"
RDEPENDS:${PN} = "networkmanager networkmanager-wifi"
