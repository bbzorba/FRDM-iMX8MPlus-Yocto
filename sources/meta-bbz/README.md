# meta-bbz: our Yocto layer for the FRDM i.MX 8M Plus

BSP: NXP **imx-6.18.37-2.1.0** (Linux 6.18, Yocto Project **6.0 "wrynose"** LTS).
Board: **FRDM-IMX8MPLUS**, `MACHINE = imx8mp-lpddr4-frdm` (not the EVK, `imx8mp-lpddr4-evk`:
different DDR setup, U-Boot and device tree). Building, flashing and the `make` commands are in the
[repository README](../../README.md); this file explains the layer and how Yocto works.

## 1. Yocto in one page

| Concept | What it is | Low-level analogy |
|---|---|---|
| **BitBake** | task scheduler; caches results by input hash | `make` with recipe tasks as targets |
| **Recipe** `foo_1.2.bb` | how to fetch, patch, build, install and package one component | one Makefile per component |
| **bbappend** `foo_%.bbappend` | changes another layer's recipe without forking it | a patch on that Makefile |
| **Class** `*.bbclass` | shared logic recipes `inherit` (cmake, kernel, image, systemd...) | an included `.mk` |
| **Layer** `meta-*` | recipes + classes + conf; the higher priority wins | a vendor tree |
| **MACHINE / DISTRO** | hardware (SoC, kernel, DTB, bootloader) / policy (init, features) | Kconfig for the whole system |
| **Image** | a recipe that installs packages into a rootfs and writes `.wic` | the firmware blob |
| **sstate-cache** | cached task outputs; why rebuilds take minutes | ccache for everything |

Task chain of every recipe (logs and runnable scripts in `tmp/work/<arch>/<recipe>/<ver>/temp/`):
`do_fetch → do_unpack → do_patch → do_configure → do_compile → do_install → do_package →
do_package_write_deb`, and for images `do_rootfs → do_image_wic`.

```bitbake
FOO = "a"     FOO ?= "a"     FOO ??= "a"          # assign / if unset / weak default
FOO += "b"    FOO:append = " b"    FOO:remove = "b"  # append now / at end of parsing / remove word
FOO:imx8mp-lpddr4-frdm = "x"                        # override: only for this MACHINE (or pn-..., arch)
```

Most useful debug command: `bitbake -e <recipe> | less` (every variable and how it got its value).

## 2. Host notes

- Packages: `build-essential chrpath cpio diffstat file gawk git python3 python3-git
  python3-jinja2 python3-pexpect socat texinfo unzip wget xz-utils zstd lz4 repo bmap-tools uuu
  picocom python3-serial`. Ubuntu 26.04 is on wrynose's tested-host list.
- **PetaLinux conflict:** sourcing PetaLinux's `settings.sh` (e.g. from `~/.bashrc`) puts its
  own `git`/`python3` first in `PATH` and breaks Yocto. The root `Makefile` and `scripts/` always
  run BitBake with `env -i`; by hand, use a clean shell.
- `build-wayland/conf/local.conf` (tracked) limits parallelism to protect the 32 GB host
  (4 tasks × `-j 4`, `-j 3` for LLVM/Clang/Node.js, `BB_PRESSURE_MAX_MEMORY`) and enables
  `rm_work`. Switching boards = change `MACHINE ??=` there; only board-specific recipes rebuild.

## 3. What the image adds on top of NXP's `imx-image-core`

| Feature | Where | Notes |
|---|---|---|
| NetworkManager (`nmcli`, `nmtui`, Wi-Fi) | `packagegroup-bbz-network`, `networkmanager_%.bbappend` | ConnMan removed |
| Wi-Fi auto-connect | `recipes-connectivity/bbz-wifi-config` | SSID/passphrase from git-ignored `site.conf`; stores only the derived WPA key (root-only) |
| apt / dpkg | `bbz-image.bb` | needs a feed of *this* build's `.deb`s (`bitbake package-index`, serve `tmp/deploy/deb`); Debian/Ubuntu archives are incompatible |
| Claude Code | `recipes-devtools/claude-code/claude-code_<ver>.bb` | official linux-arm64 binary; no strip (payload appended to the ELF); autoupdater off |
| Claude login without browser | `recipes-devtools/claude-code/bbz-claude-auth.bb` | `CLAUDE_CODE_OAUTH_TOKEN` from `site.conf` (`claude setup-token` on the PC) in root-only `/etc/claude-code/auth.env` |
| current npm | `recipes-devtools/npm/npm_<ver>.bb` | replaces the older `nodejs-npm` without rebuilding Node.js |
| Bluetooth | `recipes-connectivity/nxp-bt-enable` | **not in the image**; add it and `bluez5` back to `packagegroup-bbz-network` |
| our apps | `recipes-bbz/home-hub-node`, `hello-bbz` | see section 5 |
| M7 bootloader | `recipes-bsp/bbz-m7-boot` (source `apps/m7_bootloader`) | built with `gcc-arm-none-eabi-native`; `.bin` on the boot partition, U-Boot starts it |
| M7 start + memory lock | `recipes-bsp/u-boot` (`bbz-m7.cfg`), `recipes-bsp/imx-atf` (SiP patch) | `preboot` bootaux, `bootcmd` RDC regions + lock |
| watchdog from power-on | `recipes-bsp/u-boot` (`bbz-wdt.cfg`) | U-Boot arms and feeds WDOG1 (60 s); a frozen SoC resets itself |
| A53 heartbeat for the M7 | `recipes-bsp/bbz-m7-heartbeat` | counter in DTCM every second |
| hardware watchdog | `recipes-bsp/bbz-watchdog` | systemd arms WDOG1 (30 s); a hang cold-resets the board |
| quiet serial console | `recipes-bsp/bbz-quiet-console` | `kernel.printk = 4 4 1 7` from ~8 s into boot: only kernel errors reach J19; the Wi-Fi driver's scan messages (every 30–300 s) stay in `dmesg`. More: `dmesg -n 8` |
| NVMe not mounted at boot | `recipes-bsp/bbz-nvme-noautomount` | mounting it during boot browned the board out ([details](../../docs/README.md#power-boot-resets-and-wi-fi-errors-caused-by-the-nvme-ssd)) |
| flash without boot switch | `recipes-bsp/bbz-flash-mode`, `recipes-bsp/u-boot` | `make flash`; LED blinks while flashing |
| our device tree `imx8mp-bbz.dts`, Cortex-M7 from Linux | `recipes-kernel/linux` (patch 0002), `conf/layer.conf`, `recipes-bsp/u-boot` (`bbz-dtb.cfg`) | board DTS + M7 + which core owns which peripheral, booted by default; `imx_rpmsg_tty` loaded at boot; see [docs/M7_A53_ARCHITECTURE.md](../../docs/M7_A53_ARCHITECTURE.md) |

Not included: **Chromium** (too big to build on this host; re-add via `meta-browser/meta-chromium`)
and **snap** (no snapd for Yocto; use Docker, which is in the image, or apt with your own feed).

Updating a pinned leaf package (npm, Claude Code): rename the recipe to the new version and
replace `SRC_URI[sha256sum]`; the command is in each recipe. The rest of the BSP stays on NXP's
validated release: moving all layers to newer commits means a near-full rebuild and an untested
combination.

## 4. Wi-Fi and Bluetooth (NXP IW612, interface `mlan0`)

The onboard radio is a u-blox MAYA-W276 (NXP IW612): Wi-Fi on SDIO (`mlan0`, not `wlan0`),
Bluetooth on UART (`hci0`). Out of the box Wi-Fi did not work at all; these are the fixes, each
found on the board through the J19 console:

| Symptom | Cause | Fix |
|---|---|---|
| `mlan0` "unavailable", *Failed to D-Bus activate wpa_supplicant* | NXP's wpa_supplicant `defconfig` has `CONFIG_CTRL_IFACE_DBUS_NEW` off | `wpa-supplicant_%.bbappend` re-enables D-Bus |
| profile activated on `wfd0` instead of `mlan0` | NetworkManager managed all three driver interfaces | `bbz-wifi-config`: `uap0`/`wfd0`/`p2p-dev-*` unmanaged, profile bound to `mlan0`, no scan MAC randomization |
| boot ~2 min slow without Ethernet | NXP also enables systemd-networkd, whose wait-online waits for Ethernet | `bbz-image.bb` masks systemd-networkd |
| no `hci0` | NXP blacklists `btnxpuart`; loading it before the Wi-Fi firmware runs crashes the SDIO link | `nxp-bt-enable` loads it after `network-online.target` + 5 s |
| no Wi-Fi after some boots: `cmd53 write error=-110` or `Wakeup card timeout`, then `mlan0` gone | the IW612's SDIO link timed out on the first driver commands after boot (about 1 boot in 2 at 200 MHz, 1 in 4 at 100 MHz). Most likely supply dips: the board runs on 5 V instead of PD 15 V, and the NVMe was checked/mounted at the same time. With the NVMe idle at boot: 0 errors in 10 boots ([details](../../docs/README.md#power-boot-resets-and-wi-fi-errors-caused-by-the-nvme-ssd)). No effect: power save off, `drv_mode=1`, SDIO runtime PM off | **`bbz-nvme-noautomount`**; the 100 MHz SDIO cap and **`bbz-wifi-recover`** (driver reload, Wi-Fi back ~30 s later) stay as safety nets |

## 5. Our applications (`<repo>/apps/` + externalsrc recipes)

Each app is a normal C project with its own Makefile in `<repo>/apps/<name>/`. One small recipe
builds it into the image:

```bitbake
inherit externalsrc systemd
EXTERNALSRC = "${BBZ_APPS_DIR}/home_hub_node"     # BBZ_APPS_DIR: set in conf/layer.conf
EXTERNALSRC_BUILD = "${WORKDIR}/build"            # build output outside the source tree
EXTRA_OEMAKE = "-C ${S} BUILD='${B}' CC='${CC}' CFLAGS='${CFLAGS}' LDFLAGS='${LDFLAGS}'"
SYSTEMD_SERVICE:${PN} = "home-hub-node.service"   # installed and enabled at boot
```

- externalsrc checksums the folder, so editing the source is enough: the next build includes it.
- Pass Yocto's `CC`/`CFLAGS`/`LDFLAGS` (hardening, `--hash-style=gnu`), or the `ldflags` QA check
  fails.
- Add the package to `packagegroup-bbz-apps`. Try it on a running board with `make app-deploy`.

## 6. Learning toolbox

```bash
bitbake-layers show-layers | show-appends | show-recipes 'name*'
bitbake -e bbz-image | grep ^IMAGE_INSTALL=     # final value of a variable
bitbake -c devshell hello-bbz                   # shell in a recipe's build environment
bitbake -c cleansstate hello-bbz                # force a full rebuild of one recipe
oe-pkgdata-util find-path /usr/bin/nmcli        # which package owns a file
devtool modify linux-imx                        # edit kernel/U-Boot sources, then:
devtool finish linux-imx ../sources/meta-bbz    #   your commits become patches + a bbappend here
bitbake linux-imx -c menuconfig && bitbake linux-imx -c diffconfig   # kernel config fragment
```

Suggested path: build and deliberately break `hello-bbz`; read `bbz-image.bb` →
`imx-image-core.bb` → `image.bbclass`; trace `IMAGE_INSTALL` with `bitbake -e`; write a systemd
service recipe; later, move `local.conf` settings into your own `conf/distro/bbz.conf`.
Reference: the Yocto Mega Manual and NXP's *i.MX Yocto Project User's Guide*.

## 7. Layer contents

```
meta-bbz/
├── conf/layer.conf                                   priority 12, LAYERSERIES wrynose, BBZ_APPS_DIR,
│                                                     imx8mp-bbz.dtb in KERNEL_DEVICETREE
├── recipes-bbz/images/bbz-image.bb                   the image (+ systemd-networkd masked)
├── recipes-bbz/packagegroups/packagegroup-bbz.bb     network / devtools / apps package lists
├── recipes-bbz/hello-bbz/                            minimal C recipe to learn from
├── recipes-bbz/home-hub-node/                        our app (externalsrc + systemd)
├── recipes-bsp/bbz-flash-mode/                       reboot into U-Boot fastboot for `make flash`
├── recipes-bsp/bbz-m7-boot/                          M7 bootloader (apps/m7_bootloader)
├── recipes-bsp/bbz-m7-heartbeat/                     Linux -> M7 heartbeat
├── recipes-bsp/imx-atf/                              TF-A SiP calls: RDC regions + M7 lock
├── recipes-bsp/bbz-watchdog/                         systemd arms the hardware watchdog
├── recipes-bsp/bbz-nvme-noautomount/                 keeps udev from mounting the NVMe at boot
├── recipes-bsp/bbz-quiet-console/                    only kernel errors on the serial console
├── recipes-bsp/u-boot/u-boot-imx_%.bbappend          LED blink; imx8mp-bbz.dtb; start + lock the M7
├── recipes-connectivity/bbz-wifi-config/             Wi-Fi profile + NetworkManager settings for the IW612
├── recipes-connectivity/bbz-wifi-recover/            reloads the Wi-Fi driver if its SDIO link failed at boot
├── recipes-connectivity/networkmanager/              nmtui + Wi-Fi PACKAGECONFIG
├── recipes-connectivity/nxp-bt-enable/               loads the Bluetooth driver after Wi-Fi (not in the image)
├── recipes-connectivity/wpa-supplicant/              re-enables D-Bus in NXP's wpa_supplicant
├── recipes-devtools/claude-code/                     Claude Code binary + login token
├── recipes-devtools/npm/                             current npm
└── recipes-kernel/linux/                             SCMVERSION = "n"; patches: Wi-Fi SDIO 100 MHz,
                                                      our device tree imx8mp-bbz.dts; imx_rpmsg_tty autoload
```

`SCMVERSION = "n"`: NXP's default appends the git hash and, when git sees local changes,
`-dirty` to the kernel release. A spurious `-dirty` broke the module dependencies in `do_rootfs`;
without the hash, the kernel and all modules agree on one name.
