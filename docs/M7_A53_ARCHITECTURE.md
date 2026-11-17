# Cortex-A53 + Cortex-M7 on the i.MX 8M Plus

The i.MX 8M Plus is one chip with two kinds of CPU: **4× Cortex-A53** (up to 1.8 GHz, Linux) and
**1× Cortex-M7** (800 MHz, bare metal or an RTOS). They share one DDR controller, the on-chip
RAM and the peripherals. In `bbz-image` Linux runs on the A53s and starts M7 firmware through
remoteproc; the two sides talk over RPMsg. Everything below was tested on this board.

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
| Start/stop firmware from Linux | `R=$(dirname $(grep -l '^imx-rproc$' /sys/class/remoteproc/*/name))` (the DSP is another remoteproc); `echo <fw>.elf > $R/firmware; echo start > $R/state`; `echo stop > $R/state`. Firmware is read from `/lib/firmware` |
| Messages | the firmware announces a channel; for `rpmsg_lite_str_echo_rtos` Linux creates `/dev/ttyRPMSG30`. `imx_rpmsg_tty` is loaded at boot (it is not loaded on demand). Set the TTY to raw mode first (`stty -F /dev/ttyRPMSG30 raw -echo`): with the default terminal echo, Linux echoes the M7's replies back and the two loop forever |
| M7 console | second port of J19: `/dev/ttyACM1` on the PC, 115200 8N1 (`picocom -b 115200 /dev/ttyACM1`) |
| Start before Linux | U-Boot: `fatload mmc 2:1 0x48000000 <fw>.bin; cp.b 0x48000000 0x7e0000 0x20000; bootaux 0x7e0000` for TCM firmware. Linux then *attaches* to the running M7 instead of loading it. Use this when the M7 must be up within ~1 s of power-on |
| Debugger | JTAG/SWD on J20 (e.g. MCUXpresso + J-Link) |
| Build firmware | MCUXpresso SDK (`armgcc`, board `evkmimx8mp`), or Zephyr (`imx8mp_evk/mimx8ml8/m7`). NXP's prebuilt demos are in `/lib/firmware/imx8mp_m7_*` |

Tested on the flashed `bbz-image` with `imx8mp_m7_TCM_rpmsg_lite_str_echo_rtos.elf`:
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
| **RDC** (Resource Domain Controller) | TF-A (`plat/imx/imx8m/imx8mp/imx8mp_bl31_setup.c`) | gives every bus master a domain ID; per-peripheral (PDAP) and per-memory-region (MRC) read/write rights per domain, checked in hardware, lockable until the next reset | NXP's default: M7 = domain 1, UART2 and WDOG1 only for domain 0. **No DDR regions defined, so both cores can write all of DDR** |
| **TZASC** (TZC-380) | TF-A, fuse-enabled | Secure vs. Non-secure DDR regions (for OP-TEE) | off (not fused); it separates *worlds*, not A53 vs M7 |
| M7 **MPU** | the firmware | guards the M7 against its own bugs (stack overflow, null pointer), sets cache attributes | as configured in each firmware |

**Do you need it?** For demos and development, no: the `no-map` reservations keep Linux off the
M7's memory. For a system where a Linux fault must not be able to corrupt the M7 (e.g. a
safety- or control-critical M7), yes: add RDC memory regions in TF-A that make the M7's
DDR region writable only by domain 1, and assign its peripherals (UART4, CAN, …) to domain 1
with PDAP, locked. Keep the RPMsg vrings and pool writable by both. In the other direction, an
M7 bug can still overwrite Linux memory unless a region forbids domain 1 there. A blocked
access returns a bus error to the offending core, and the RDC records its address and domain.

## 6. Watchdogs and resetting the board

| | A53 / Linux | M7 |
|---|---|---|
| Watchdog | **WDOG1**, `/dev/watchdog0`. systemd arms it at boot (`RuntimeWatchdogSec=30s`, recipe `bbz-watchdog`) and pets it; if Linux hangs, it fires after 30 s | **WDOG3**, disabled for Linux; the M7 firmware arms and pets it itself |
| On timeout | WDOG1 drives `WDOG_B` (`fsl,ext-reset-output`) → the PMIC **cold-resets the whole board**: A53s, M7, everything | resets the SoC, or only signals an interrupt, as the firmware configures it |

The two cores share one reset: there is no separate "reset only the A53s". To restart only the
M7, Linux uses remoteproc (`echo stop`/`echo start > …/state`). Tested: after `echo c >
/proc/sysrq-trigger` (kernel crash, `kernel.panic=0`, so no automatic reboot) the watchdog reset
the board 29 s later and it booted normally; a SoC freeze from a bad M7 start was caught the
same way. The PMIC's power-on status (`i2cget -f -y 0 0x25 0x05`) then has bit 6 (`WDOG_B`,
0x40) set. The watchdog doesn't survive a reboot, so U-Boot and `make flash` aren't affected.

**A hardware reset line from the PC:** J19 can't do it. Its CH342F USB-UART chip has RTS/DTR
outputs, but they aren't connected on this board. The reset input is **J20 (JTAG) pin 10,
`JTAG_RESET`**, which pulls `SYS_nRST` (cold reset) through diode D19. A JTAG probe (J-Link,
MCU-Link) can drive it, or anything that shorts it to GND (an open-drain output of a USB GPIO
adapter, a relay contact; ground is on J20 too). Pulling the same pin through D18 to `POR_B` (warm reset) is
not populated on this board. Rework alternative: wire the CH342F `RTS0` pin to J20 pin 10, so
toggling RTS on `/dev/ttyACM0` resets the board.

