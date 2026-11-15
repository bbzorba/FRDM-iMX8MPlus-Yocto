# imx-yocto-bsp: custom Yocto image for the FRDM i.MX 8M Plus

Yocto build tree for **`bbz-image`**, a custom Linux image for NXP's
**FRDM-IMX8MPLUS** development board (i.MX 8M Plus, 4 GB LPDDR4, 32 GB eMMC).

- BSP: NXP **imx-6.18.37-2.1.0** (Linux 6.18, Yocto Project **6.0 "wrynose"** LTS)
- `DISTRO = fsl-imx-wayland`, `MACHINE = imx8mp-lpddr4-frdm`, Debian packaging (`apt`/`dpkg`)
- Image: NXP's `imx-image-core` + NetworkManager (Wi-Fi auto-connect) + apt + Node.js 22 with
  current npm + **Claude Code** (prebuilt arm64 binary) + dev tools + our apps
  (`home-hub-node` service, `hello-bbz`)

| Where | What |
|---|---|
| **[sources/meta-bbz/README.md](sources/meta-bbz/README.md)** | Yocto learning guide: concepts, recipes, packages, flashing, devtool |
| **[docs/README.md](docs/README.md)** | the board: every module (Wi-Fi/BT/802.15.4, CAN, M.2, cameras...), sensors, Cortex-M7, NXP manuals |
| **[apps/home_hub_node/README.md](apps/home_hub_node/README.md)** | our application: build, deploy, test ([architecture](apps/home_hub_node/ARCHITECTURE.md)) |
| [sources/meta-imx/README.md](sources/meta-imx/README.md) | NXP's own BSP README |

## Layout

```
imx-yocto-bsp/
├── README.md                 this file
├── Makefile                  everyday commands: make image / flash / ssh / monitor ... (below)
├── apps/                     our applications, built into the image by Yocto recipes
│   └── home_hub_node/        C service that reports board metrics to the ESP32-C6 Home Hub
├── docs/                     board documentation + NXP manuals and schematic
├── setup-environment         → sources/base/setup-environment: enter a build dir
├── imx-setup-release.sh      → sources/meta-imx/tools/...: create a NEW build dir (NXP EULA)
├── sources/                  layers
│   ├── meta-bbz/             ← our layer: image, packagegroups, app/tool recipes, bbappends
│   └── ...                   NXP / Yocto / OpenEmbedded layers (git submodules, pinned commits)
├── build-wayland/
│   └── conf/                 local.conf + bblayers.conf are tracked; site.conf (secrets) and the rest are ignored
├── scripts/                  helper scripts for long unattended builds (below)
├── downloads/                fetched source tarballs   (ignored, ~11 GB)
└── .repo/                    NXP `repo` tool metadata (ignored)
```

## Everyday commands (`make`)

Run `make` for the list. All targets work from the repository root, in any shell: BitBake is
started in a clean environment, so `~/.bashrc` can't interfere.

| Command | What it does |
|---|---|
| `make image` | build `bbz-image`, with `scripts/mem-guard.sh` running during the build |
| `make app` | build only the application recipe (`APP=home-hub-node`) |
| `make status` | progress, temperatures, RAM and new errors of the current or last build |
| `make lsusb` | list boards waiting in USB serial-download mode (`sudo uuu -lsusb`) |
| `make wait-usb` | block until a board appears in serial-download mode |
| `make flash` | flash the eMMC with uuu. Stops with instructions if no board is in serial-download mode |
| `make flash-sd DEV=/dev/sdX` | write an SD card. Refuses non-removable disks and partitions, and asks before erasing |
| `make find` | check whether the board answers on the network |
| `make ssh` | shell on the board |
| `make monitor` | live board status every 5 s: temperatures, RAM, disk, network, service, failed units |
| `make logs` | follow the application's journal |
| `make app-deploy` | rebuild the app and install its package on the running board (no reflash) |
| `make console` | serial console on J19 (first port = Linux) |

`BOARD` defaults to `root@imx8mp-lpddr4-frdm.local`: the image runs avahi, so the board is found
by name. Override it with `BOARD=root@<ip>`. SSH skips host-key checks, because every reflash
creates new keys. Typical loop after a code change in `apps/`: `make app-deploy`, then
`make logs`. For a release: `make image && make flash`.

## Clone and build on a new machine

```bash
git clone --recursive https://github.com/bbzorba/FRDM-iMX8MPlus-Yocto.git ~/imx-yocto-bsp
cd ~/imx-yocto-bsp
# Already cloned without --recursive?  git submodule update --init

source setup-environment build-wayland        # existing conf/ is reused
bitbake bbz-image
```

Host packages, the PetaLinux `PATH` warning and the EULA are covered in section 1 of
[the guide](sources/meta-bbz/README.md). `build-wayland/conf/local.conf` accepts NXP's EULA
(`ACCEPT_FSL_EULA = "1"`); read `sources/meta-freescale/EULA` before building on a new machine.

The submodules are pinned to the commits of NXP's `imx-6.18.37-2.1.0` manifest, so a clone
reproduces this exact BSP. On this machine the layers are also managed by NXP's `repo` tool
(`.repo/`, not in git): `repo sync` would move them to newer commits. After an intentional
update, record the new commits with `git add sources/<layer> && git commit`.

Output: `build-wayland/tmp/deploy/images/imx8mp-lpddr4-frdm/`
- `bbz-image-imx8mp-lpddr4-frdm.rootfs.wic.zst` (+ `.wic.bmap`): full disk image
- `imx-boot-imx8mp-lpddr4-frdm-sd.bin-flash_evk`: bootloader (SPL + DDR firmware + ATF + U-Boot)

The first build takes 10–14 hours on an 8-core / 32 GB host. Rebuilds after small changes take
minutes, thanks to the shared-state cache.

## Flash the FRDM-IMX8MPLUS

Board connectors (see NXP's FRDM-IMX8MPLUS Quick Start Guide):

| Part | What |
|---|---|
| **SW5** | 4-way boot-mode DIP switch (1 = ON) |
| **SW3** | power slide switch |
| **J2**  | USB-C power input (PD charger, ≥ 45 W) |
| **J3**  | USB-C USB 3.0 OTG, the **flashing** port (corner, next to the 40-pin header) |
| **J19** | USB-C debug UART (115200 8N1) |

| Boot from | SW5 1-2-3-4 |
|---|---|
| Serial download (`uuu`) | `0001` |
| eMMC (factory default)  | `0010` |
| microSD                 | `0011` |

**eMMC** (internal storage, via the SoC's USB serial-download mode):

```bash
# power off, SW5 = 0001, connect J3 to the PC, power on
sudo uuu -lsusb                                   # should list an MX865 "SDPS:" device
cd build-wayland/tmp/deploy/images/imx8mp-lpddr4-frdm
sudo uuu -b emmc_all imx-boot-imx8mp-lpddr4-frdm-sd.bin-flash_evk \
    'bbz-image-imx8mp-lpddr4-frdm.rootfs.wic.zst/*'   # "/*" = decompress on the fly
# ends with "Success 1  Failure 0"; then power off, SW5 = 0010, power on
```

**microSD**: write to the whole card (`/dev/sdX`, not `/dev/sdX1`), then set SW5 = `0011`:

```bash
lsblk -o NAME,SIZE,RM,TRAN,MODEL                  # find the card; this erases it
cd build-wayland/tmp/deploy/images/imx8mp-lpddr4-frdm
sudo bmaptool copy bbz-image-imx8mp-lpddr4-frdm.rootfs.wic.zst /dev/sdX
```

Log in as `root` (no password: a dev-only default) on the J19 console (`sudo picocom -b 115200
/dev/ttyUSB0`) or over SSH.

**Wi-Fi at boot:** create `build-wayland/conf/site.conf` (git-ignored, read automatically by
BitBake) with `BBZ_WIFI_SSID = "..."` and `BBZ_WIFI_PSK = "..."`, then rebuild. The board then
joins that network by itself through NetworkManager. Without it, use `nmtui` on the board. Details
are in the meta-bbz README.

## Helper scripts (`scripts/`)

They locate the BSP from their own path, so they work from any directory. They default to the
`build-wayland` build dir (override with `BUILD_DIR=...`). Their output files stay in `scripts/`
and are git-ignored.

| Script | What it does | Output |
|---|---|---|
| `auto-build.sh [image]` | Waits until no BitBake is running, then runs `bitbake -k <image>` (default `bbz-image`) in a clean environment (`env -i`, so `~/.bashrc` / PetaLinux can't leak in). | `auto-build.log` |
| `check-build.sh` | One-shot status: progress, tasks running now, CPU/NVMe temperature, RAM, swap, disk, and only the ERROR lines that are new since the last call. | `.check-build.state` |
| `mem-guard.sh` | Last-resort RAM guard. If available RAM drops below ~1.2 GB, it kills the largest compiler so that one task fails instead of the PC freezing. Run it during a build, stop it afterwards. | `mem-guard.log` |
| `build-health.sh` | Logs temperatures, CPU/GPU power, load, disk, swap and throttling every 10 min until BitBake stops. | `build-health.csv` |

Typical unattended build:

```bash
nice -n 19 scripts/mem-guard.sh &  guard=$!
scripts/build-health.sh &
scripts/auto-build.sh          # blocks until the build ends; watch with scripts/check-build.sh
kill $guard
```

## Host-safety settings (in `build-wayland/conf/local.conf`)

The default parallelism (8 tasks × `make -j8`) ran this 32 GB host out of RAM and froze it.
`local.conf` limits it:
- `BB_NUMBER_THREADS = "4"`, `PARALLEL_MAKE = "-j 4"`, and `-j 3` for LLVM, Clang and Node.js
- `BB_PRESSURE_MAX_MEMORY` stops BitBake from starting new tasks under memory pressure
- `rm_work` deletes each recipe's build tree once it's packaged (saves ~100 GB)

On the host itself, **earlyoom** (`sudo apt install earlyoom`) runs at boot as a system-wide
safety net. Ubuntu's `systemd-oomd` reacted too late here: the PC froze twice, once with no build
running, while GNOME's file indexer `localsearch-3` grew past 8 GB. `/etc/default/earlyoom` on this
host:

```sh
EARLYOOM_ARGS="-r 3600 -m 5,3 -s 100,100 --prefer (^|/)(localsearch-3|cc1plus|cc1|ld|ld.bfd|ld.gold|lld|rustc|node|java)$ --avoid (^|/)(gnome-shell|Xwayland|systemd|sshd|claude|claude-desktop|Cooker|bitbake-server|bitbake-worker)$"
```

It sends SIGTERM when available RAM drops below 5 % (~1.5 GB), and SIGKILL below 3 %. It picks
the indexer and compilers first, and never the desktop or BitBake itself. Kills are logged in
`journalctl -u earlyoom`. Don't quote the regexes: systemd keeps quote characters literally.
`scripts/mem-guard.sh` is the narrower build-only guard.

## Notes and fixes kept in meta-bbz

- **Wi-Fi (`mlan0`) and Bluetooth (`hci0`) needed five fixes** before they worked at boot:
  wpa_supplicant without D-Bus, NetworkManager grabbing `uap0`/`wfd0`, an SDIO crash when NM
  started too early, systemd-networkd delaying `network-online.target`, and the Bluetooth
  driver being blacklisted. Each is explained in the
  [meta-bbz README](sources/meta-bbz/README.md) (NetworkManager section).
- Without an HDMI screen, `weston.service` fails at boot (no display to drive). That is
  harmless; the rest of the system doesn't depend on it.

- `recipes-kernel/linux/linux-imx_%.bbappend` sets `SCMVERSION = "n"`. Otherwise the kernel's git
  check can mark the release as `-dirty`, which breaks the out-of-tree module dependencies
  (jailhouse) in `do_rootfs`.
- If the PC ever crashes mid-build, a recipe can fail later with "uid not found / host
  contamination" (lost pseudo database) or strange link errors (truncated object files). Fix with
  `bitbake -c clean <recipe>` and build again.
