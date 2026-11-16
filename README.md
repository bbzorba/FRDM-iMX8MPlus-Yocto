# FRDM-iMX8MPlus-Yocto

Yocto build tree for **`bbz-image`**, a custom Linux image for NXP's **FRDM-IMX8MPLUS** board
(i.MX 8M Plus, 4 GB LPDDR4, 32 GB eMMC), plus our applications and board documentation.

- BSP: NXP **imx-6.18.37-2.1.0** (Linux 6.18, Yocto Project **6.0 "wrynose"** LTS),
  `DISTRO = fsl-imx-wayland`, `MACHINE = imx8mp-lpddr4-frdm`, Debian packaging
- Image: NXP's `imx-image-core` + NetworkManager (Wi-Fi auto-connect) + Bluetooth + apt +
  Node.js 22 with current npm + Claude Code + dev tools + our apps

| Document | Content |
|---|---|
| [sources/meta-bbz/README.md](sources/meta-bbz/README.md) | our layer, Yocto basics, Wi-Fi/Bluetooth fixes |
| [docs/README.md](docs/README.md) | the board: modules, sensors, Cortex-M7, NXP manuals |
| [apps/home_hub_node/README.md](apps/home_hub_node/README.md) | our app ([architecture](apps/home_hub_node/ARCHITECTURE.md)) |

```
imx-yocto-bsp/
├── Makefile              everyday commands (below)
├── apps/                 our applications, built into the image by recipes in meta-bbz
├── docs/                 board documentation + NXP manuals and schematic
├── scripts/              build and flashing helpers
├── sources/meta-bbz/     our layer;  sources/<other>: NXP/Yocto layers as pinned git submodules
├── build-wayland/conf/   local.conf + bblayers.conf tracked; site.conf (secrets) git-ignored
├── setup-environment     enter the build dir;  imx-setup-release.sh: create a new one
└── downloads/, .repo/    fetched sources, NXP repo metadata (git-ignored)
```

## Build

```bash
git clone --recursive https://github.com/bbzorba/FRDM-iMX8MPlus-Yocto.git ~/imx-yocto-bsp
cd ~/imx-yocto-bsp
make image            # = bitbake bbz-image in a clean environment, RAM guard on
```

The first build takes 10–14 hours on an 8-core / 32 GB host; later builds take minutes.
`local.conf` accepts NXP's EULA (`ACCEPT_FSL_EULA = "1"`): read `sources/meta-freescale/EULA` first.
Output: `build-wayland/tmp/deploy/images/imx8mp-lpddr4-frdm/` (`bbz-image-…rootfs.wic.zst` = full
disk image, `imx-boot-…-sd.bin-flash_evk` = bootloader).

**`build-wayland/conf/site.conf`** (git-ignored, read by BitBake automatically) holds secrets:

| Variable | Effect |
|---|---|
| `BBZ_WIFI_SSID`, `BBZ_WIFI_PSK` | Wi-Fi joined at boot (stored as the derived WPA key, root-only) |
| `BBZ_CLAUDE_OAUTH_TOKEN` | Claude Code login without browser (`claude setup-token` on the PC) |
| `BBZ_ANTHROPIC_API_KEY` | alternative: Anthropic Console API key |

Only hashes of these reach BitBake's signature files, but the built `.wic` contains them: don't
share it.

## Everyday commands

| Command | What it does |
|---|---|
| `make image` / `make app` | build the image / only `APP` (default `home-hub-node`) |
| `make status` | build progress, temperatures, RAM, new errors |
| `make flash` | flash the eMMC over USB-C **J3**, **no boot-switch change** (details below) |
| `make flash-recovery` | flash via the Boot ROM: SW5 = `0001`, J3; works with an empty or broken eMMC |
| `make flash-sd DEV=/dev/sdX` | write an SD card (refuses system disks and partitions, asks first) |
| `make lsusb` / `make wait-usb` | list / wait for a board in serial-download mode |
| `make find` / `make ssh` | check the board on the network / open a shell |
| `make monitor` / `make logs` | live board status every 5 s / follow the app's journal |
| `make app-deploy` | rebuild the app and install it on the running board (no reflash) |
| `make console` | serial console on J19. **Exit: Ctrl+A, then Ctrl+X** (Ctrl+C goes to the board) |

`BOARD` defaults to `root@imx8mp-lpddr4-frdm.local` (avahi); override with `BOARD=root@<ip>`.
Login: `root` without password (dev-only default).

## Flashing

| Part | What |
|---|---|
| **SW5** | boot-mode DIP switch (1 = ON): `0010` eMMC (normal), `0011` microSD, `0001` serial download |
| **SW3** / **J2** | power switch / USB-C power in (PD charger ≥ 45 W) |
| **J3** | USB-C OTG: all image data goes here |
| **J19** | USB-C debug UART: `/dev/ttyACM0` = Linux console, `/dev/ttyACM1` = Cortex-M7 |

Boot chain: Boot ROM → SPL (DDR setup) → ARM Trusted Firmware → U-Boot → Linux. SPL, ATF and
U-Boot form `imx-boot` in the eMMC boot partition.

**`make flash`:** if the board answers over SSH, `bbz-flash-mode` on the board leaves a one-time
`boot.scr` and reboots. U-Boot runs it, the script deletes itself (a power cut then boots
normally) and starts `fastboot usb 0`. uuu writes image + bootloader (`scripts/flash-fastboot.uuu`)
and the board resets into the new system; the LED `led-0` blinks meanwhile. Without network,
`scripts/uboot.py` reaches U-Boot over J19 instead. Without J3, fastboot can't start and the board
boots its old system.

**Why the image can't go over J19:** fastboot and uuu are USB protocols. J19 is a USB-to-UART
bridge, and U-Boot here only accepts up to 115200 baud: the 684 MB gzipped image would take about
16.5 h, against ~4 min over J3.

## Scripts (`scripts/`)

They find the BSP from their own path and run BitBake with `env -i` (so a PetaLinux `.bashrc`
can't interfere). Logs stay in `scripts/` and are git-ignored.

| Script | Purpose |
|---|---|
| `auto-build.sh [image]` | wait for any running BitBake, then `bitbake -k <image>` |
| `check-build.sh` | one-shot build status (used by `make status`) |
| `mem-guard.sh` | kills the largest compiler if RAM gets critical (used by `make image`) |
| `build-health.sh` | logs temperatures, power, disk, swap every 10 min during a build |
| `uboot.py fastboot\|reset` | drive U-Boot over J19 (needs `python3-serial`) |
| `flash-fastboot.uuu`, `flash-recovery.uuu` | uuu scripts for `make flash` / `make flash-recovery` |

## Host notes

- `local.conf` limits parallelism (4 tasks × `-j 4`, `-j 3` for LLVM/Clang/Node.js,
  `BB_PRESSURE_MAX_MEMORY`) and enables `rm_work`: the defaults froze this 32 GB PC.
- **earlyoom** runs on the host as a freeze guard (`/etc/default/earlyoom`: SIGTERM below 5 %
  available RAM, file indexer and compilers first, never the desktop or BitBake).
- After a host crash mid-build, a recipe may fail with "uid not found / host contamination" or
  odd link errors: `bitbake -c clean <recipe>` and build again.
- Without an HDMI screen, `weston.service` fails at boot; nothing else depends on it.
- Submodules are pinned to NXP's manifest; `repo sync` on this machine would move the layers.
  Record intentional updates with `git add sources/<layer> && git commit`.
