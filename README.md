# imx-yocto-bsp: custom Yocto image for the FRDM i.MX 8M Plus

Yocto build tree for **`bbz-image`**, a custom Linux image for NXP's
**FRDM-IMX8MPLUS** development board (i.MX 8M Plus, 4 GB LPDDR4, 32 GB eMMC).

- BSP: NXP **imx-6.18.37-2.1.0** (Linux 6.18, Yocto Project **6.0 "wrynose"** LTS)
- `DISTRO = fsl-imx-wayland`, `MACHINE = imx8mp-lpddr4-frdm`, Debian packaging (`apt`/`dpkg`)
- Image: NXP's `imx-image-core` + NetworkManager + apt + Node.js/npm + dev tools + `hello-bbz`

The full learning guide (Yocto concepts, recipes, packages, flashing, devtool) is in
**[sources/meta-bbz/README.md](sources/meta-bbz/README.md)**. NXP's own BSP README is at
[sources/meta-imx/README.md](sources/meta-imx/README.md).

## Layout

```
imx-yocto-bsp/
├── README.md                 this file
├── setup-environment         → sources/base/setup-environment: enter a build dir
├── imx-setup-release.sh      → sources/meta-imx/tools/...: create a NEW build dir (NXP EULA)
├── sources/                  layers
│   ├── meta-bbz/             ← our layer: image, packagegroups, recipes, bbappends (plain files here)
│   └── ...                   NXP / Yocto / OpenEmbedded layers (git submodules, pinned commits)
├── build-wayland/
│   └── conf/                 local.conf + bblayers.conf are tracked; everything else is ignored
├── scripts/                  helper scripts for long unattended builds (below)
├── downloads/                fetched source tarballs   (ignored, ~11 GB)
└── .repo/                    NXP `repo` tool metadata (ignored)
```

## Clone and build on a new machine

```bash
git clone --recursive <this-repo-url> ~/imx-yocto-bsp
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
/dev/ttyUSB0`) or over SSH. Then set up the network with `nmtui`.

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

## Notes and fixes kept in meta-bbz

- `recipes-kernel/linux/linux-imx_%.bbappend` sets `SCMVERSION = "n"`. Otherwise the kernel's git
  check can mark the release as `-dirty`, which breaks the out-of-tree module dependencies
  (jailhouse) in `do_rootfs`.
- If the PC ever crashes mid-build, a recipe can fail later with "uid not found / host
  contamination" (lost pseudo database) or strange link errors (truncated object files). Fix with
  `bitbake -c clean <recipe>` and build again.
