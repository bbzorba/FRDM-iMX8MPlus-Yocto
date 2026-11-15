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

FILES:${PN} = "${sysconfdir}/NetworkManager/system-connections"
ALLOW_EMPTY:${PN} = "1"
RDEPENDS:${PN} = "networkmanager networkmanager-wifi"
