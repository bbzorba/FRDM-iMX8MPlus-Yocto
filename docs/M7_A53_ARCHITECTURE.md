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
| Start/stop firmware from Linux | `R=/sys/class/remoteproc/remoteproc0` (name `imx-rproc`); `echo <fw>.elf > $R/firmware; echo start > $R/state`; `echo stop > $R/state`. Firmware is read from `/lib/firmware` |
| Messages | the firmware announces a channel; for `rpmsg_lite_str_echo_rtos` Linux creates `/dev/ttyRPMSG30`. `imx_rpmsg_tty` is loaded at boot (it is not loaded on demand). Set the TTY to raw mode first (`stty -F /dev/ttyRPMSG30 raw -echo`): with the default terminal echo, Linux echoes the M7's replies back and the two loop forever |
| M7 console | second port of J19: `/dev/ttyACM1` on the PC, 115200 8N1 (`picocom -b 115200 /dev/ttyACM1`) |
| Start before Linux | U-Boot: `fatload mmc 2:1 0x48000000 <fw>.bin; cp.b 0x48000000 0x7e0000 0x20000; bootaux 0x7e0000` for TCM firmware. Linux then *attaches* to the running M7 instead of loading it. Use this when the M7 must be up within ~1 s of power-on |
| Debugger | JTAG/SWD on J20 (e.g. MCUXpresso + J-Link) |
| Build firmware | MCUXpresso SDK (`armgcc`, board `evkmimx8mp`), or Zephyr (`imx8mp_evk/mimx8ml8/m7`). NXP's prebuilt demos are in `/lib/firmware/imx8mp_m7_*` |

Tested on the flashed `bbz-image` with `imx8mp_m7_TCM_rpmsg_lite_str_echo_rtos.elf`:
remoteproc state `running`, `/dev/ttyRPMSG30` echoed every message back, stop and restart
work, the M7 console printed each message it got, and Wi-Fi, Bluetooth and the app were
unaffected.

### What `bbz-image` changes for this

| Change | Why |
|---|---|
| U-Boot boots `imx8mp-frdm-rpmsg.dtb` by default (`m7-rpmsg.cfg`) | the default `imx8mp-frdm.dtb` has no M7 node, so remoteproc only sees the DSP |
| U-Boot `PREBOOT` adds `clk-imx8mp.mcore_booted clk_ignore_unused` to the kernel command line | Linux otherwise switches off clocks it isn't using itself, but the M7's peripherals (UART4, audio) need them. Without these arguments, starting the M7 froze the whole SoC |

Costs of the rpmsg device tree: the **audio codec, SAI3, PDM mic, UART4, ECSPI2, FlexCAN1, PWM4
and FlexSPI belong to the M7** (Linux doesn't drive them), unused clocks stay on (slightly more power), and about 290 MB of
DDR is reserved for the M7. To go back: `setenv fdtfile imx8mp-frdm.dtb; saveenv` in U-Boot.

## 3. Memory map

Addresses as seen by each core (from the reference manual, the device tree and the
firmware ELFs):

| Memory | M7 address | A53 address | Size | Used for |
|---|---|---|---|---|
| ITCM | `0x0000_0000` | `0x007E_0000` | 128 KB | M7 code (`*_TCM_*` firmware) — fastest, M7 only at full speed |
| DTCM | `0x2000_0000` | `0x0080_0000` | 128 KB | M7 data and stack |
| OCRAM | `0x0090_0000` | `0x0090_0000` | 512 KB | TF-A (BL31) runs at `0x0097_0000`: don't use it from the M7 |
| DDR `m4_reserved` | `0x8000_0000` | same | 16 MB | `*_DDR_*` firmware code+data; non-cacheable data of the TCM firmware |
| DDR vrings | `0x5500_0000`, `0x5500_8000` | same | 2× 32 KB | RPMsg ring buffers |
| DDR `rsc_table` | `0x550F_F000` | same | 4 KB | resource table: where the M7 tells Linux about its vrings |
| DDR `vdevbuffer` | `0x5540_0000` | same | 1 MB | RPMsg message payloads |
| DDR `audio_reserved`, `micfil` | `0x8100_0000`, `0x9100_0000` | same | 256 MB + 16 MB | M7 audio buffers |
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
