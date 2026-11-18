# Cortex-A53 + Cortex-M7 on the i.MX 8M Plus

The i.MX 8M Plus is one chip with two kinds of CPU: **4× Cortex-A53** (up to 1.8 GHz, Linux) and
**1× Cortex-M7** (800 MHz, bare metal or an RTOS). They share one DDR controller, the on-chip
RAM and the peripherals. In `bbz-image` U-Boot starts our M7 bootloader (`bbz-m7-boot`) before Linux and locks the
M7's memory; Linux runs on the A53s. Everything below was tested on this board.

## 1. Architecture

```
   Cortex-A53 x4 (Linux, domain 0)                  Cortex-M7 (firmware, domain 1)
  ┌────────────────────────────────┐              ┌──────────────────────────────┐
  │ remoteproc: load ELF, start/stop│──── MU ────▶│ 128 KB ITCM / 128 KB DTCM     │
  │ virtio_rpmsg_bus + imx_rpmsg_tty│◀─── MU ─────│ 32 KB I-cache / 32 KB D-cache │
  │ /dev/ttyRPMSG30                 │  (doorbells) │ rpmsg-lite (FreeRTOS)         │
  │ L1 + 512 KB L2, coherent (SCU)  │              │ MPU, no link to the A53 caches│
  └───────────────┬────────────────┘              └──────────────┬───────────────┘
                  │ AXI                                           │ AXI
  ┌───────────────┴───────────────────────────────────────────────┴───────────────┐
  │ NoC interconnect + RDC (resource domain checks per master and peripheral)      │
  └────┬────────────────────┬────────────────────────────┬───────────────────────┘
       │                    │                            │
  ┌────┴─────────┐   ┌──────┴─────────┐          ┌───────┴───────────────────────┐
  │ OCRAM 512 KB │   │ peripherals     │          │ DDR controller + PHY → 4 GB   │
  │ (TF-A BL31   │   │ UART2: A53 only │          │ LPDDR4: Linux RAM, M7 code/   │
  │  lives here) │   │ UART4: M7 console          │ data, RPMsg vrings + buffers  │
  └──────────────┘   └─────────────────┘          └───────────────────────────────┘
```

- **Control path**: Linux's `imx_rproc` driver copies the M7 ELF into TCM/DDR, writes the M7's
  start address and releases it from reset. Stopping puts it back into reset.
- **Data path**: RPMsg over virtio. Two ring buffers (*vrings*) and a 1 MB message pool sit in
  DDR. The **Messaging Unit (MU)** only carries doorbell interrupts ("there is a new
  message in vring N"); the data itself never goes through the MU.
- **Separate consoles**: the A53 console is UART2 (first J19 port), the M7 console is UART4
  (second J19 port).

## 2. How to reach the M7

| Way | How |
|---|---|
| Start/stop firmware from Linux (only without the lock, see section 5) | `R=$(dirname $(grep -l '^imx-rproc$' /sys/class/remoteproc/*/name))` (the DSP is another remoteproc); `echo <fw>.elf > $R/firmware; echo start > $R/state`; `echo stop > $R/state`. Firmware is read from `/lib/firmware` |
| Messages | the firmware announces a channel; for `rpmsg_lite_str_echo_rtos` Linux creates `/dev/ttyRPMSG30`. `imx_rpmsg_tty` is loaded at boot (it is not loaded on demand). Set the TTY to raw mode first (`stty -F /dev/ttyRPMSG30 raw -echo`): with the default terminal echo, Linux echoes the M7's replies back and the two loop forever |
| M7 console | second port of J19: `/dev/ttyACM1` on the PC, 115200 8N1 (`picocom -b 115200 /dev/ttyACM1`) |
| Start before Linux (**default**) | U-Boot `preboot`: `fatload mmc ${mmcdev}:${mmcpart} 0x48000000 bbz-m7-boot.bin; cp.b 0x48000000 0x7e0000 0x20000; bootaux 0x7e0000`. Linux then *attaches* to the running M7 (remoteproc state `attached`). See section 7 |
| Debugger | JTAG/SWD on J20 (e.g. MCUXpresso + J-Link) |
| Build firmware | MCUXpresso SDK (`armgcc`, board `evkmimx8mp`), or Zephyr (`imx8mp_evk/mimx8ml8/m7`). NXP's prebuilt demos are in `/lib/firmware/imx8mp_m7_*` |

Tested (before the M7 lock of section 5 existed) with `imx8mp_m7_TCM_rpmsg_lite_str_echo_rtos.elf`:
remoteproc state `running`, `/dev/ttyRPMSG30` echoed every message back, stop and restart
work, the M7 console printed each message it got, and Wi-Fi, Bluetooth and the app were
unaffected.

### Our device tree: `imx8mp-bbz.dts`

U-Boot boots `imx8mp-bbz.dtb`, our own device tree
(`sources/meta-bbz/recipes-kernel/linux/files/0002-arm64-dts-add-imx8mp-bbz.patch`). It includes
the board DTS `imx8mp-frdm.dts`, which describes all FRDM hardware on top of the SoC description
`imx8mp.dtsi`. It then adds, in five commented sections:

1. memory reserved for the M7 (`no-map`): firmware region and RPMsg buffers;
2. the M7 node for remoteproc (`fsl,imx8mp-cm7`, mailbox MU1, its memory regions);
3. **which core owns which peripheral**;
4. the clocks of M7-owned peripherals, kept on with `init-on-array`;
5. the A53 watchdog's reset output.

| Core | Peripherals |
|---|---|
| **M7** | UART4 (console, J19 port 2), FlexCAN1 (CAN on J27), PWM4, WDOG3 |
| **Linux** | everything else: UART2 console, UART3, I2C1–3 (PMIC, codec, GPIO expanders), audio (WM8962, SAI3, PDM mic) + HiFi4 DSP, ECSPI2 (802.15.4 radio), QSPI NOR, Ethernet, USB, PCIe/NVMe, Wi-Fi/BT, display, camera, WDOG1 |
| shared | DDR, OCRAM, MU1 (doorbells), GPIO banks (pin by pin) |

**Moving a peripheral to the M7:** add `&<node> { status = "disabled"; };` in section 3 and its
root clock (`IMX8MP_CLK_<NAME>_ROOT` from `include/dt-bindings/clock/imx8mp-clock.h`) to
`init-on-array` in section 4. Linux then neither drives the device nor claims its pins; the M7
firmware sets up the pins and the device. **Back to Linux:** delete both again. Missing
hardware is added the same way: a node or `&label { … }` override in this one file. Hardware
enforcement of the split (RDC) is configured in TF-A, see section 5.

One setting can't live in the device tree: the kernel argument `clk-imx8mp.mcore_booted`.
U-Boot adds it on every boot (`PREBOOT="run prepare_mcore"`, `bbz-dtb.cfg`). Linux then leaves the
gates of the SoC's root clocks alone. Without it, starting M7 firmware froze the whole SoC (the
watchdog reset it 30 s later), even with the M7's peripheral clocks in `init-on-array`; with
it, start/stop and RPMsg work. (`clk_ignore_unused` also works but keeps every unused clock on.)

Compared with NXP's `imx8mp-frdm-rpmsg.dtb`, Linux keeps audio and the DSP, the M7 gets CAN and
PWM, and only 18 MB of DDR is reserved instead of ~290 MB.

## 3. Memory map

Addresses as seen by each core (from the reference manual, the device tree and the
firmware ELFs):

| Memory | M7 address | A53 address | Size | Used for |
|---|---|---|---|---|
| ITCM | `0x0000_0000` | `0x007E_0000` | 128 KB | M7 code (`*_TCM_*` firmware) — fastest, M7 only at full speed |
| DTCM | `0x2000_0000` | `0x0080_0000` | 128 KB | M7 data and stack |
| OCRAM | `0x0090_0000` | `0x0090_0000` | 512 KB | TF-A (BL31) runs at `0x0097_0000`: don't use it from the M7 |
| DDR `m7_reserved` | `0x8000_0000` | same | 16 MB | `*_DDR_*` firmware code+data; non-cacheable data of the TCM firmware |
| DDR vrings | `0x5500_0000`, `0x5500_8000` | same | 2× 32 KB | RPMsg ring buffers |
| DDR `rsc_table` | `0x550F_F000` | same | 4 KB | resource table: where the M7 tells Linux about its vrings |
| DDR `vdevbuffer` | `0x5540_0000` | same | 1 MB | RPMsg message payloads |
| DDR rest | — | `0x4000_0000`… | ~3.7 GB | Linux |

All reserved regions are `no-map` in the device tree: Linux never allocates them and doesn't
even map them, except the RPMsg pool, which it maps itself (non-cacheable) for messages.

## 4. The DDR controller and simultaneous access

There is nothing to enable in Yocto: the **DDR controller** (a Synopsys uMCTL2 with its PHY) is
set up by the SPL on every boot (the `DDRINFO: start DRAM init … DRAM rate 4000MTS` lines on the
console: training of the LPDDR4 timings). It is shared by every bus master: A53 cluster, M7,
GPU, VPU, NPU, DMA engines.

**Simultaneous access** is handled in hardware, with no software involvement:

1. The **NoC** queues the AXI requests of all masters and arbitrates between them
   (configurable QoS priorities; display and other real-time masters can be ranked higher).
2. The **DDR controller** keeps read/write queues and reorders requests to keep DRAM pages
   open, so the cores *share bandwidth*; a busy GPU or NPU adds latency to M7 DDR accesses.
3. Each single bus transfer is atomic, but **nothing locks a data structure** across cores.

What the hardware does **not** do is keep the caches **coherent**: the A53s are coherent among
themselves, but the M7's caches are invisible to them and the other way round. So for memory
both sides use:

- map it **non-cacheable** on both sides (Linux does this for the RPMsg pool; the NXP firmware
  puts its shared region in a non-cacheable MPU region — the `0x8000_0000` segment above), or
  clean/invalidate the caches by hand around each hand-over;
- hand buffers over with a protocol: RPMsg's vrings have one writer per field and an MU
  interrupt as the signal, so they need no lock. For your own shared structures use the MU, or a
  single-writer ring buffer, never plain spin locks: without coherency, a lock word can sit
  stale in one core's cache.

**For timing-critical M7 code**, run from TCM (`*_TCM_*` firmware): TCM is private to the M7
and has fixed single-cycle access, so DDR load from Linux, GPU or NPU can't delay it. DDR-based
code is cached but sees variable latency on a cache miss.

## 5. Memory protection

**The DDR controller does not protect anything automatically**: it serves every request that
reaches it. Protection is configured in three places:

| Layer | Configured in | What it does | State in this image |
|---|---|---|---|
| `reserved-memory` + `no-map` | device tree | Linux's allocator never hands out those pages | **on** for all M7 regions. Software-only: a kernel bug or root via `/dev/mem` can still write there |
| **RDC** (Resource Domain Controller) | TF-A (`plat/imx/imx8m/imx8mp/imx8mp_bl31_setup.c`) | gives every bus master a domain ID; per-peripheral (PDAP) and per-memory-region (MRC) read/write rights per domain, checked in hardware, lockable until the next reset | M7 = domain 1 (NXP's default). **bbz: the M7's TCM and DDR window are M7 read/write, A53 read-only, locked** (below) |
| **TZASC** (TZC-380) | TF-A, fuse-enabled | Secure vs. Non-secure DDR regions (for OP-TEE) | off (not fused); it separates *worlds*, not A53 vs M7 |
| M7 **MPU** | the firmware | guards the M7 against its own bugs (stack overflow, null pointer), sets cache attributes | as configured in each firmware |

**What `bbz-image` does (tested).** U-Boot's `bootcmd` calls two SiP functions added to TF-A
(`recipes-bsp/imx-atf`), right before Linux:

| Call | Effect |
|---|---|
| `smc 0xc20000b0 <region> <start> <end> <mrc>` | sets RDC region 22 (M7 TCM, A53-view addresses `0x7E0000–0x81F000`: ITCM + DTCM except the last 4 KB, the shared heartbeat page) and region 39 (DRAM `0x80000000–0x81000000`, encoded `(addr − 0x40000000) / 2`) to `0xC000000E` = locked, M7 read/write, A53 read-only. U-Boot reads numbers as **hex**: region 22 = `0x16`, 39 = `0x27` |
| `smc 0xc20000b1` | lock: no further region changes; TF-A refuses M7 start/stop/reset from Linux (`IMX_SIP_SRC`); `SRC_M7RCR` = `0xC20000A8` (domain control on, locked, writable by the M7 only) |

Test results: A53 writes (`devmem2`) to `0x80000000`, ITCM and DTCM are silently dropped (value
unchanged, no bus error); the shared page stays writable; `echo stop` on the remoteproc fails
("Failed to stop remote core") and the M7 keeps running; the M7's own check of its DDR pattern
reports `DDR=ok` (without the lock it reported `DDR=CORRUPTED` after the same writes). Other
bus masters (DMA, PCIe, USB) are in domain 0 too and are blocked the same way. Not covered yet:
the M7's peripherals (PDAP for UART4, FlexCAN1, …), and the other direction (an M7 bug can still
write Linux memory). Because Linux can't load firmware into the locked TCM any more, NXP's
remoteproc demos need the lock removed from `bootcmd`.

## 6. Watchdogs, per-core reset and remote reset

| | A53 / Linux | M7 |
|---|---|---|
| Watchdog | **WDOG1** (`/dev/watchdog0`), armed by U-Boot (60 s), then systemd (30 s, `bbz-watchdog`) | **WDOG3**, armed by `bbz-m7-boot` (2 s) |
| On timeout | `WDOG_B` → PMIC cold reset of the **whole board** (A53s *and* M7). There is no A53-only watchdog; WDOG1's reset can only be masked (`SRC_A53RCR0`) | **M7 only** (`SRC_M7RCR.WDOG3_RST_OPTION` = 0, the default) |
| Tested | kernel crash → board reset 31 s later; the M7 was reset too | firmware stops feeding → M7 rebooted 2 s later ("boot #2, reset by WDOG3"); Linux uptime continued |

**Separate resets.** Both cores are in one chip: the reset pins (`POR_B`, `SYS_nRST`, RESET
button, J20 pin 10) reset the whole SoC. Per-core resets exist only in software, in the reset
controller (SRC): `SRC_M7RCR` resets/enables the M7 (remoteproc and WDOG3 use it; tested),
`SRC_A53RCR0/1` reset single A53 cores. A clean A53-only restart is not supported by NXP's boot
flow (cores restart into TF-A's warm-boot path; a full Linux boot reruns the SPL, which
re-initialises the DDR) and was not tested.

**WDOG1 from power-on.** U-Boot already arms WDOG1 (60 s, `bbz-wdt.cfg`) and feeds it while it
runs (prompt, fastboot: a full `make flash` of 4 min 41 s passed); Linux's driver keeps feeding it
until systemd takes over. Tested: a deliberately hung U-Boot reset the board after 60 s, so a
SoC freeze at any stage (e.g. a faulty M7 firmware stalling the bus) no longer needs a hand on
the RESET button.

**A53 crash with the M7 running.** The M7 reports "A53 heartbeat LOST … M7 keeps running"
within 5 s and keeps working until WDOG1 restarts the whole board (30 s in `bbz-image`, at most
128 s). Tested twice with WDOG1 at 120 s: the M7 ran 90–110 s after a kernel crash, and none of
its clock, bus, PLL or power-controller registers changed. With WDOG1 completely disabled the M7
froze 20–55 s after the crash in 3 of 3 tests; that mechanism is not understood yet, so WDOG1
stays armed. After the board reset U-Boot starts the M7 again 1.8 s later.

**Remote reset from the PC (`make reset`).** No reset line is wired to the PC (J19's CH342F
RTS/DTR are not connected; J20 pin 10 needs a probe or relay). Instead `bbz-m7-boot` takes `RR`
on its console (J19 port 2, `/dev/ttyACM1`) and resets the SoC through WDOG3 switched to
global reset. Works while Linux runs and while U-Boot hangs (tested: Linux login again
~30 s later). If the whole SoC is frozen, WDOG1 resets it (see above). `make reset` falls back to
`systemctl reboot` over SSH.

## 7. Boot flow, storage and the M7 bootloader

**Storage.** No on-chip flash: the SoC has a boot ROM, OCRAM and the M7's 256 KB TCM. The board
has 32 GB eMMC (boot device; hardware boot partition with `imx-boot` = SPL + TF-A + U-Boot at
offset 0; user area: FAT boot partition with `Image`, DTBs, `bbz-m7-boot.bin`, and the ext4
rootfs), 32 MB QSPI NOR on FlexSPI (unused; the M7 could execute from it in place at
`0x08000000`), microSD and the NVMe SSD. **Both cores share all of it; the M7 has no flash of
its own.** The boot ROM only starts A53 core 0; the M7 is held in reset until software on the
A53 releases it.

**Boot sequence (measured from `reboot`, both consoles timestamped):**

| Time | Who | What |
|---|---|---|
| 0.4 s | A53 | boot ROM → SPL from eMMC boot partition, DDR training |
| 0.9 s | A53 | TF-A (BL31) |
| 1.0 s | A53 | U-Boot |
| **1.8 s** | A53 → M7 | U-Boot `preboot` loads `bbz-m7-boot.bin` into ITCM (`0x7E0000` = M7 `0x0`) and `bootaux` releases the M7; its banner appears at once (M7 at 200 MHz from `sys_pll2_200m`) |
| 4 s | A53 | `bootcmd`: RDC regions + lock, then the kernel starts |
| 15.8 s | M7 | first Linux heartbeat |
| 31 s | A53 | Linux login |

**`bbz-m7-boot`** (`apps/m7_bootloader`, ~3.7 KB code + 112 B data in TCM): MPU set first
(peripheral space = Device; without it the M7's speculative reads broke U-Boot's eMMC and froze
Linux), UART4 console, WDOG3, A53 heartbeat supervision (Linux writes a counter to DTCM
`0x2001F000` = A53 `0x0081F000` every second, `bbz-m7-heartbeat`), a 4 KB pattern in its DDR
window checked every second, `RR` remote reset, and an application slot at `0x80100000`
(header magic `BPPA`, stack, entry; `j` jumps there). Commands: `make m7-console`, then `h`.

**Trying M7 firmware safely.** Never put an untested M7 build into the image or onto the boot
partition: U-Boot starts it at every boot, and one that freezes the SoC then loops the board
(WDOG1 resets it every 60 s). Load it by hand instead, at the U-Boot prompt, before the lock:
`smc 0xc2000005 0x2` (stop the M7), `loady 0x48000000` (YMODEM from the PC, e.g. `sb`),
`cp.b 0x48000000 0x7e0000 0x20000`, `bootaux 0x7e0000`, `boot`. A reset brings back the firmware
from the boot partition. If a bad one does get there: stop autoboot, hold the M7 in reset
(`mw.l 0x3039000C 0xa9`, within ~1 s), `loady` a good one and `fatwrite mmc 2:1 0x48000000
bbz-m7-boot.bin <size>`.

**RAM.** Linux sees 3.58 GB and uses ~350 MB at idle (largest user: Docker's `containerd`,
71 MB). The M7 has 256 KB TCM and the 16 MB DDR window. A larger M7 application (an RTOS
with 1–2 MB of code and up to ~1 MB of data) doesn't fit the TCM: it runs from the DDR window
with the M7 caches on (MPU region cacheable), time-critical code and ISRs in ITCM. A Yocto recipe
can build such an image with `gcc-arm-none-eabi-native` (as `bbz-m7-boot` does) and put it on the
boot partition or into the application slot, so `make flash` deploys it together with Linux.

**Hardening (not done yet):** drop Docker/containerd, Claude Code, Node.js,
compilers and `-dev` packages; root password and SSH keys only; `/dev/mem` off
(`CONFIG_DEVMEM=n`, `STRICT_DEVMEM`) once nothing needs it (the heartbeat would then go via
RPMsg/MU); kernel lockdown and module signing; remove unused drivers (camera/ISP, VPU, GPU/NPU
if unused, HDMI audio); RDC PDAP for the M7's peripherals; read-only rootfs.

