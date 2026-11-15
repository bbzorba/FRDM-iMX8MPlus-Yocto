# meta-bbz: custom i.MX 8M Plus image with Yocto

BSP: NXP **imx-6.18.37-2.1.0** (Linux 6.18, Yocto Project **6.0 "wrynose"** LTS).
Target: **FRDM i.MX 8M Plus** development board (`MACHINE = imx8mp-lpddr4-frdm`; 4 GB LPDDR4,
32 GB eMMC). The bigger 8M Plus EVK would be `imx8mp-lpddr4-evk`. The two boards need different
bootloaders (DDR setup) and device trees, so always build for the board you actually have.
If your board is a third-party SoM (Variscite, Toradex, Phytec, Compulab...), use the vendor's
BSP layer and `MACHINE` instead. Everything else in this guide still applies.

The repository README one level up ([../../README.md](../../README.md)) covers cloning, the
FRDM connectors, flashing commands and the helper scripts in `scripts/`.

```
~/imx-yocto-bsp/
├── imx-setup-release.sh      NXP wrapper: creates a build dir, writes local.conf/bblayers.conf
├── setup-environment         re-enters an existing build dir
├── scripts/                  helpers for unattended builds (auto-build, mem-guard, check-build...)
├── sources/                  all layers (git submodules, also managed by `repo` on this host)
│   ├── bitbake/              the build engine (Python)
│   ├── openembedded-core/    "meta": core recipes and classes (gcc, glibc, busybox, apt, ...)
│   ├── meta-openembedded/    meta-oe, meta-networking (NetworkManager), meta-python, ...
│   ├── meta-freescale*/      community i.MX BSP
│   ├── meta-imx/             NXP's release layers (kernel linux-imx, u-boot-imx, GPU/VPU, images)
│   ├── meta-browser/         Chromium / Firefox (not used by bbz-image)
│   └── meta-bbz/             ← YOUR layer (this directory)
├── downloads/                fetched source tarballs (DL_DIR)
└── build-wayland/            conf/ (tracked in git), tmp/, sstate-cache/
```

---

## 0. The mental model (read this first)

| Concept | What it is | Low-level analogy |
|---|---|---|
| **BitBake** | Task scheduler + metadata parser. Runs tasks, caches results by input hash. | `make`, but its "targets" are tasks of recipes |
| **Recipe** `foo_1.2.bb` | How to fetch, patch, configure, compile, install and package ONE piece of software | One Makefile per component |
| **bbappend** `foo_%.bbappend` | Modifies another layer's recipe without forking it | A patch on the Makefile |
| **Class** `*.bbclass` | Shared logic recipes `inherit` (autotools, cmake, meson, kernel, image...) | An included `.mk` |
| **Layer** `meta-*` | A git repo grouping recipes, classes and conf. Priority decides who wins. | A vendor tree |
| **conf**: `local.conf`, `bblayers.conf`, `machine/*.conf`, `distro/*.conf` | Global variables. MACHINE = hardware (SoC tune, kernel, DTB, bootloader). DISTRO = policy (init system, libc, features). | Kconfig for the whole system |
| **Package** | Output of a recipe after `do_package`, split into `foo`, `foo-dev`, `foo-dbg`... (.deb here) | |
| **Image** | A recipe whose job is to install packages into a rootfs and produce `.wic` / `.ext4` / `.tar` | The final firmware blob |
| **sstate-cache** | Cached task outputs, keyed by a hash of all inputs. This is why rebuilds are fast. | ccache for everything |

Every recipe runs this task chain. Each step has a log and a runnable script under
`tmp/work/<arch>/<recipe>/<version>/temp/` (`log.do_compile`, `run.do_compile`):

```
do_fetch → do_unpack → do_patch → do_configure → do_compile → do_install
        → do_package → do_package_write_deb → (image) do_rootfs → do_image_wic
```

Variable syntax you'll see everywhere:

```bitbake
FOO = "a"            # assign
FOO ?= "a"           # assign if unset
FOO ??= "a"          # weak default (lowest priority)
FOO += "b"           # append with space (immediately)
FOO:append = " b"    # append at the END of parsing (note the leading space); wins over later '='
FOO:remove = "b"     # remove word b, applied last
FOO:imx8mp-lpddr4-frdm = "x"  # OVERRIDE: only when MACHINE (or DISTRO, arch, pn-...) matches
${@python_expr}      # inline Python
```

The single most useful debug command: `bitbake -e <recipe> | less` dumps every variable's final
value AND the history of how it got there (search for `# $FOO`).

---

## 1. Host setup (already done on this machine)

```bash
sudo apt install build-essential chrpath cpio debianutils diffstat file gawk gcc git \
  iputils-ping libacl1 locales python3 python3-git python3-jinja2 python3-pexpect \
  python3-pip python3-subunit socat texinfo unzip wget xz-utils zstd lz4 repo bmap-tools uuu
```

Ubuntu 26.04 is on wrynose's officially tested host list (`SANITY_TESTED_DISTROS`).

> ⚠️ **PetaLinux conflict.** `~/.bashrc` sources `~/Desktop/Embedded_Systems/PetaLinux/settings.sh`,
> which puts PetaLinux's own `git`, `python3`, `gawk`... first in `PATH`. That breaks Yocto (and
> already breaks plain `git clone` over HTTPS). **Always work from a clean shell**:
>
> ```bash
> alias yocto-shell='env -i HOME=$HOME USER=$USER TERM=$TERM LANG=C.UTF-8 \
>   PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash --noprofile --norc'
> ```
> Put that alias in `~/.bash_aliases`, then type `yocto-shell` before any Yocto work.
> Alternatively, only source PetaLinux on demand instead of in `.bashrc`.

## 2. Fetch the BSP (already done)

```bash
mkdir ~/imx-yocto-bsp && cd ~/imx-yocto-bsp
repo init -u https://github.com/nxp-imx/imx-manifest -b imx-linux-wrynose -m imx-6.18.37-2.1.0.xml
repo sync -j8
```

`repo` reads the XML manifest and clones each layer at a pinned commit. `repo status`,
`repo forall -c 'git log -1 --oneline'` and `repo sync` (to update) are the day-to-day commands.

The whole tree is also one git repo: `meta-bbz`, `scripts/` and `build-wayland/conf/` are normal
files, and every other layer is a submodule pinned to the manifest's commit. On another machine,
`git clone --recursive <repo-url>` replaces the `repo init` / `repo sync` steps.

## 3. Create the build directory

```bash
yocto-shell
cd ~/imx-yocto-bsp
DISTRO=fsl-imx-wayland MACHINE=imx8mp-lpddr4-frdm source imx-setup-release.sh -b build-wayland
# → shows NXP EULA; read it and accept. You are now in build-wayland/

bitbake-layers add-layer ../sources/meta-bbz
bitbake-layers show-layers
```

Next time, re-enter an existing build with:

```bash
yocto-shell
cd ~/imx-yocto-bsp && source setup-environment build-wayland
```

To switch boards in an existing build dir, change `MACHINE ??=` at the top of
`build-wayland/conf/local.conf` and run `bitbake bbz-image` again. Only the board-specific recipes
(kernel, U-Boot, imx-boot, image) are rebuilt: about 30 minutes here.

### Tune `build-wayland/conf/local.conf`

This is what was appended here (the file is tracked in git):

```bitbake
# Optional: keep downloads + sstate outside the build dir so you can delete/recreate builds freely
# DL_DIR = "${HOME}/yocto-cache/downloads"
# SSTATE_DIR = "${HOME}/yocto-cache/sstate-cache"

# 8 cores / 32 GB RAM host. The defaults (8 tasks x -j8 = 64 compilers) ran the PC out of
# RAM and froze it. Max 16 compilers, fewer for the RAM-hungry recipes:
BB_NUMBER_THREADS = "4"
PARALLEL_MAKE = "-j 4"
PARALLEL_MAKE:pn-llvm-native = "-j 3"
PARALLEL_MAKE:pn-llvm = "-j 3"
PARALLEL_MAKE:pn-clang-native = "-j 3"
PARALLEL_MAKE:pn-nodejs = "-j 3"
PARALLEL_MAKE:pn-nodejs-native = "-j 3"
# Don't start new tasks while the kernel reports memory stalls >1 % of the time
BB_PRESSURE_MAX_MEMORY = "10000"
XZ_THREADS = "4"
ZSTD_THREADS = "4"

# Delete each recipe's work dir after it's built: saves ~60-70 % disk.
# Remove a recipe from rm_work when you want to inspect its build tree:
INHERIT += "rm_work"
# RM_WORK_EXCLUDE += "hello-bbz linux-imx"

# Already set by NXP's script, shown here so you know where apt comes from:
# PACKAGE_CLASSES = "package_deb"
# EXTRA_IMAGE_FEATURES += "package-management"
```

> 💾 Disk: without `rm_work`, every recipe keeps its unpacked sources and build tree, so a build
> grows past 100 GB. Keep `rm_work` on.

> 🧯 For long unattended builds, `scripts/` (top of the repo) has an auto-restarting `bitbake -k`
> runner, a RAM guard that kills the biggest compiler before the PC can freeze, and a status
> checker. See the repository README.

## 4. Build

Start small, so you learn the loop on something that builds in minutes:

```bash
bitbake hello-bbz                       # toolchain + your recipe
bitbake -c listtasks hello-bbz
ls tmp/work/armv8a-poky-linux/hello-bbz/1.0/    # WORKDIR: sources/, image/ (= ${D}), packages-split/, temp/ (logs)
                                                 # (with rm_work on, add hello-bbz to RM_WORK_EXCLUDE to keep it)
```

Then the image. The first build is long (several hours), mostly the toolchain, LLVM, Qt, Node.js,
Docker and the GPU stack.

```bash
bitbake bbz-image
```

Output lands in `tmp/deploy/images/imx8mp-lpddr4-frdm/`:

| File | Use |
|---|---|
| `bbz-image-imx8mp-lpddr4-frdm.rootfs.wic.zst` | full disk image: bootloader + boot partition + rootfs |
| `bbz-image-...rootfs.wic.bmap` | block map for fast `bmaptool` flashing |
| `imx-boot-imx8mp-lpddr4-frdm-sd.bin-flash_evk` | bootloader container (SPL + ATF + U-Boot + DDR firmware), for SD and eMMC alike; "flash_evk" is just NXP's target name |
| `Image`, `imx8mp-frdm*.dtb` | kernel + device trees |

## 5. The packages you asked for

### NetworkManager ✅ (included)
From `meta-openembedded/meta-networking`. `packagegroup-bbz-network` installs the daemon, `nmcli`,
`nmtui` and the Wi-Fi plugin. `recipes-connectivity/networkmanager/networkmanager_%.bbappend`
enables `nmtui` + `wifi` PACKAGECONFIG. ConnMan is removed from the image so they don't fight.
On the board: `nmcli dev wifi connect <SSID> password <pw>` or just `nmtui`.

### apt ✅ (included)
NXP's setup already sets `PACKAGE_CLASSES = "package_deb"`, so every recipe produces `.deb`s and
the image ships `apt`/`dpkg` + a package DB. **But** apt needs a feed of packages built by *your*
Yocto build. Debian/Ubuntu archives are **not** compatible (different glibc, compiler flags,
package splits) and will break the system.

Serve your own feed:

```bash
# host
bitbake package-index                     # generates Packages indexes in tmp/deploy/deb/
cd tmp/deploy/deb && python3 -m http.server 8000
```

Bake the feed URL into the image (local.conf):

```bitbake
PACKAGE_FEED_URIS = "http://<host-ip>:8000"
PACKAGE_FEED_BASE_PATHS = "."
PACKAGE_FEED_ARCHS = "all armv8a armv8a-mx8mp imx8mp_lpddr4_frdm"   # = dirs in tmp/deploy/deb/
```

Then on the board, `apt update && apt install <anything you've bitbake'd>`.

### Chromium ❌ (removed: too big for this host)
Chromium was dropped from the image: its source alone unpacks to ~23 GB, and compiling it took
most of the RAM/disk/time of the whole build on an 8-core / 32 GB machine.
To bring it back later: `bitbake-layers add-layer ../sources/meta-browser/meta-chromium`, add
`chromium-ozone-wayland` to `IMAGE_INSTALL` in `bbz-image.bb`, and set
`PARALLEL_MAKE:pn-chromium-ozone-wayland = "-j 3"` in local.conf. On the board, as root:
`chromium --no-sandbox --ozone-platform=wayland`.

### Claude Code ⚙️ (installed on the board, not baked in)
Claude Code is a proprietary CLI you log into with your account, so the clean approach is to put
its prerequisites in the image and install it on the target. `packagegroup-bbz-devtools` already
includes `nodejs`, `nodejs-npm`, `git` and `ca-certificates`. On the board (linux-arm64, glibc):

```bash
curl -fsSL https://claude.ai/install.sh | bash        # native installer
# or:  npm install -g @anthropic-ai/claude-code
claude
```

It needs internet access, so set up the network with NetworkManager first.

### snap ❌ (not supported, and deliberately left out)
There is **no snapd recipe** in any layer of this BSP (`meta-oe/.../snappy` is the Google
compression library, not snapd). snapd expects Ubuntu's AppArmor profiles, its own confinement
setup and the Snap Store's base snaps (core22/core24), none of which exist on a Yocto distro. Old
community layers for it are unmaintained. If you need "install apps without rebuilding the image",
use:
- **Docker**: `imx-image-core` (which `bbz-image` builds on) already installs `docker` on i.MX8,
  via meta-virtualization. You can run Ubuntu userspace in a container on the board.
- **apt** with your own feed (above).
If you truly need snaps, you want Ubuntu Core / Ubuntu for i.MX, not Yocto.

## 6. Flash the board

FRDM-IMX8MPLUS parts used here (from NXP's Quick Start Guide): **SW5** boot-mode DIP switch
(1 = ON), **SW3** power slide switch, **J2** USB-C power in (PD charger ≥ 45 W), **J3** USB-C
USB 3.0 OTG (flashing), **J19** USB-C debug UART. There's no "recovery button" like on Jetson
boards: the SW5 position tells the SoC's BootROM where to boot from.

| Boot from | SW5 1-2-3-4 |
|---|---|
| Serial download (`uuu`), the Jetson "recovery mode" equivalent | `0001` |
| eMMC (factory default) | `0010` |
| microSD | `0011` |

### Option A: microSD (to try an image without touching the eMMC)

```bash
lsblk -o NAME,SIZE,RM,TRAN,MODEL        # identify the card, e.g. /dev/sdb. TRIPLE-CHECK, this erases it.
cd tmp/deploy/images/imx8mp-lpddr4-frdm
sudo umount /dev/sdX?* 2>/dev/null
sudo bmaptool copy bbz-image-imx8mp-lpddr4-frdm.rootfs.wic.zst /dev/sdX   # whole card, not sdX1
# or without bmap:
# zstdcat bbz-image-imx8mp-lpddr4-frdm.rootfs.wic.zst | sudo dd of=/dev/sdX bs=4M conv=fsync status=progress
```

Set **SW5 = `0011`**, insert the card and power on.

### Option B: eMMC with `uuu` (NXP's USB flasher, via the SoC's serial-download mode)
`uuu` is NXP's counterpart of NVIDIA's `flash.sh`. In serial-download mode, the BootROM waits on USB.
uuu loads the bootloader into RAM and starts it. U-Boot then exposes the eMMC over fastboot, and
uuu streams the image into it.

1. Power off, **SW5 = `0001`**, connect **J3** (the corner USB-C next to the 40-pin header) to the
   PC, keep power on J2, power on with SW3.
2. Check and flash:
   ```bash
   sudo uuu -lsusb                       # lists "MX865 SDPS:" (MX865 = i.MX 8M Plus)
   cd tmp/deploy/images/imx8mp-lpddr4-frdm
   sudo uuu -b emmc_all imx-boot-imx8mp-lpddr4-frdm-sd.bin-flash_evk \
       'bbz-image-imx8mp-lpddr4-frdm.rootfs.wic.zst/*'      # "/*" = decompress while writing
   ```
   This takes about 4 minutes and ends with `Success 1  Failure 0`.
3. Power off, **SW5 = `0010`** (eMMC), power on.

### Serial console
Connect **J19** to the PC. You're already in `dialout`.

```bash
sudo picocom -b 115200 /dev/ttyUSB0     # or /dev/ttyACM0; try the others if silent
```

Log in as `root` with no password. This is a dev-only default from NXP's
`root-login-with-empty-password` fragment; remove it before any product.

First-boot checks: `uname -a`, `hello-bbz`, `nmcli dev`, `apt --version`, `systemctl --failed`.

## 7. Learning toolbox (use these constantly)

```bash
bitbake-layers show-layers               # layers + priorities
bitbake-layers show-recipes 'chromium*'  # which layer provides what
bitbake-layers show-appends              # every bbappend and what it touches
bitbake-layers show-overlayed            # recipes overridden by a higher-priority layer
bitbake -e bbz-image | grep ^IMAGE_INSTALL=
bitbake -g bbz-image && bitbake -g -u taskexp bbz-image   # dependency graph / GUI explorer
bitbake -c devshell hello-bbz            # shell inside the recipe's build env (CC, CFLAGS set)
bitbake -c cleansstate hello-bbz         # force a full rebuild of one recipe
oe-pkgdata-util list-pkg-files hello-bbz # which files went into which package
oe-pkgdata-util find-path /usr/bin/nmcli # which package owns a file
cat tmp/deploy/images/*/bbz-image-*.rootfs.manifest   # every package in the image
buildhistory: add INHERIT += "buildhistory" to see diffs between image builds
```

### devtool: the workflow for kernel/U-Boot/driver work
```bash
devtool modify linux-imx            # checks the kernel source out into build/workspace/sources/linux-imx
# ... edit drivers / DTS, commit with git ...
devtool build linux-imx
bitbake bbz-image                   # image now uses your workspace kernel
devtool finish linux-imx ../sources/meta-bbz   # turns your commits into patches + a bbappend in YOUR layer
```

Same for `u-boot-imx` and `imx-atf`. Kernel config:

```bash
bitbake linux-imx -c menuconfig
bitbake linux-imx -c diffconfig     # writes a .cfg fragment with just your changes; drop it into
                                    # meta-bbz/recipes-kernel/linux/files/ and add it to SRC_URI in a
                                    # linux-imx_%.bbappend
```

New software from a tarball or git URL: `devtool add <name> <url>` generates a recipe for you.
Read it to see what it guessed.

### Suggested learning path
1. Build `hello-bbz`, break it on purpose (typo in the C file), read `log.do_compile`.
2. Read `bbz-image.bb` → `imx-image-core.bb` → `core-image.bbclass` → `image.bbclass`.
3. Use `bitbake -e` to trace where `IMAGE_INSTALL` and `DISTRO_FEATURES` get their values.
4. Add a DT overlay or GPIO driver via `devtool modify linux-imx`, then `devtool finish`.
5. Write a systemd service recipe (`inherit systemd`, `SYSTEMD_SERVICE:${PN}`).
6. Make your own `DISTRO` conf in meta-bbz (`conf/distro/bbz.conf` with `require conf/distro/fsl-imx-wayland.conf`)
   instead of editing local.conf. That's how real products are structured.
7. Reference: the Yocto Mega Manual (docs.yoctoproject.org), and NXP's *i.MX Yocto Project User's
   Guide* + *i.MX Linux User's Guide* for this release.

## Layer contents

```
meta-bbz/
├── conf/layer.conf                                          layer registration (priority, compat)
├── recipes-bbz/images/bbz-image.bb                          the image
├── recipes-bbz/packagegroups/packagegroup-bbz.bb            package lists
├── recipes-bbz/hello-bbz/hello-bbz_1.0.bb (+ files/)        minimal C recipe to learn from
├── recipes-connectivity/networkmanager/networkmanager_%.bbappend
└── recipes-kernel/linux/linux-imx_%.bbappend                SCMVERSION = "n" (see below)
```

### Why `linux-imx_%.bbappend` sets `SCMVERSION = "n"`
NXP's default (`SCMVERSION = "y"`) appends the git hash to the kernel release and, if git reports
local changes, `-dirty`. Inside the build that check reported a clean tree as dirty. The kernel
package became `kernel-6.18.37-…-dirty`, while jailhouse's module depended on the name without
`-dirty`, so `do_rootfs` failed with "unmet dependencies". With `SCMVERSION = "n"`, the release
name comes only from the recipe, and the kernel and all modules agree.
