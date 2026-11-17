# Board documentation: FRDM-IMX8MPLUS

What's on the board, how to reach its parts from Linux, and NXP's vendor documents.
Building and flashing are in the [top-level README](../README.md).

## Documents

Vendor documents for the FRDM-IMX8MPLUS board and the i.MX 8M Plus SoC,
downloaded from nxp.com (November 2026).

| File | Content |
|---|---|
| `FRDM-IMX8MPLUS_UM12340_board_user_manual.pdf` | Board user manual (UM12340 Rev 1.0): connectors, boot switch SW5, debug UARTs, USB |
| `FRDM-IMX8MPLUS_schematic_SPF-95022_B1.pdf` | Board schematic, rev B1 |
| `FRDM-IMX8MPLUS-SCH-95022_B1.zip` | Design package: schematic, BOM, OrCAD sources |
| `FRDM-IMX8MPLUS_quick_start_guide.pdf` | Quick start guide |
| `FRDM-IMX8MPLUS_block_diagram.pdf` | Board block diagram |
| `IMX8MPRM_reference_manual.pdf` | SoC reference manual (Rev 3): System Boot (ch. 6), Cortex-M7, peripherals |
| `IMX8MPLUSFS_fact_sheet.pdf` | SoC fact sheet |

Not included (need an NXP login): datasheet IMX8MPCEC, Hardware
Developer's Guide IMX8MPHDG, Security Reference Manual IMX8MPSRM (NDA).

## What's on the board

From UM12340, Table 1 (features) and Table 3 (connectors). The last column says what the
`bbz-image` already supports.

| Part | Details | In the image |
|---|---|---|
| **SoC** i.MX 8M Plus | 4× Cortex-A53 up to 1.8 GHz, **Cortex-M7** 800 MHz, **NPU 2.3 TOPS**, ISP, H.264/H.265 VPU, 3D/2D GPU | A53 runs Linux; NPU/GPU/VPU drivers come with NXP's `imx-image-core`; **M7 usable from Linux** (see below) |
| **Tri-radio module** u-blox MAYA-W276-00B (NXP IW612) | **Wi-Fi 6**, **Bluetooth 5.4 / BLE**, **IEEE 802.15.4** (Thread / Zigbee / Matter). On SDIO, UART, SPI, SAI | **Wi-Fi works** on `mlan0` (not `wlan0`): driver `nxp-wlan` + IW612 firmware + NetworkManager, auto-connect via `bbz-wifi-config` (see the meta-bbz README for the fixes it needed, incl. automatic recovery when the SDIO link fails at boot). **Bluetooth/BLE works**: `hci0` via `nxp-bt-enable` + BlueZ, central and peripheral roles (a 10 s scan found 26 devices). 802.15.4/Thread: layers present (`meta-nxp-openthread`, `meta-nxp-matter-baseline`) but no packages in the image yet |
| Memory | 4 GB LPDDR4, 32 GB eMMC 5.1, 32 MB QSPI NOR, microSD 3.0 (J17) | eMMC = boot device |
| **Ethernet** | 2× Gigabit RJ45 (J5, J7), PHY YT8521. ETH1 is a **PoE** powered device, ETH2 supports **TSN** | yes |
| **M.2 Key M 2280** (J23) | PCIe, for an **NVMe SSD** (one is fitted on this board) or an NPU card | PCIe + NVMe drivers; one empty ext4 partition, **not mounted at boot** (see below): `mount /dev/nvme0n1p1 /mnt` |
| M.2 Key E 2230 (J24, bottom) | USB, SDIO, SAI, UART, I2C. Wired to the onboard radio by default; using the slot needs resistor rework (see schematic) | — |
| **USB** | USB 3.0 Type-C OTG (J3, also the `uuu` flashing port), USB 3.0 Type-A host (J4) | yes |
| Displays | HDMI 2.0 (J16), MIPI-DSI 4-lane (J15), 2× LVDS 4-lane (J8, J9) with backlight connectors | HDMI with Weston; DSI/LVDS panels via device-tree variants (`imx8mp-frdm-*-panel.dtb`) |
| **Cameras** | 2× MIPI-CSI 4-lane (J13, J14) | via device-tree variants (`imx8mp-frdm-os08a20*.dtb`, `-ap1302-dual.dtb`) |
| **CAN + I2C** (J27, 2x5) | high-speed CAN transceiver TJA1051T/3, I2C expansion, reset and on/off signals; CAN termination DIP switch | owned by the **M7** in `imx8mp-bbz.dtb`; for SocketCAN give FlexCAN1 back to Linux (`can-utils` not in the image yet) |
| **40-pin expansion** (J18) | Raspberry-Pi style: GPIO, I2C, UART, SPI, I2S | `i2c-tools` in the image; GPIO via `/dev/gpiochip*` |
| Audio | WM8962 codec: headphone/mic jack (J21), speaker plug (J22) | ALSA |
| **Debug** USB-C J19 (CH342F) | **two serial ports**: 1st = Cortex-A53 Linux console, 2nd = Cortex-M7 console. JTAG 10-pin (J20) | `make console`. On the PC they appear as `/dev/ttyACM0` (Linux) and `/dev/ttyACM1` (M7) |
| Other | RTC with coin-cell connector (P2), fan header, ON/OFF, RESET and USER buttons, RGB LED | |
| Power | USB-C PD input (J2, 12–20 V, ≥ 45 W charger), aux DC in (J1), PMIC PCA9450 | currently only 5 V on J2, see below |

## Sensors the kernel exposes

Two die temperatures (`cpu-thermal`, `soc-thermal`; trip point 85 °C) and the USB-C PD
controller's voltage/current monitor, which only reads non-zero while the port supplies
power. The board has no environment sensors (add them on the 40-pin header or J27).

## Cortex-M7

The M7 is inside the same SoC, not a separate chip. `bbz-image` boots our own device tree,
`imx8mp-bbz.dtb`, so Linux can start M7 firmware and exchange messages with it:

```bash
R=$(dirname $(grep -l '^imx-rproc$' /sys/class/remoteproc/*/name))   # the M7
echo imx8mp_m7_TCM_rpmsg_lite_str_echo_rtos.elf > $R/firmware
echo start > $R/state                              # M7 console: /dev/ttyACM1 on the PC
stty -F /dev/ttyRPMSG30 raw -echo                  # else the echo loops forever
echo hello > /dev/ttyRPMSG30                       # the demo echoes it back
```

The M7 owns UART4, FlexCAN1 and PWM4; everything else stays with Linux. Which core owns what,
how to move a peripheral, memory map, DDR sharing, memory protection, watchdogs and the reset
line: [M7_A53_ARCHITECTURE.md](M7_A53_ARCHITECTURE.md).

## Power: boot resets and Wi-Fi errors caused by the NVMe SSD

**Input power.** The board is designed for USB-C PD on J2 at **12–20 V** (45 W charger): VSYS
feeds the PMIC and the converters, among them a dedicated **3.3 V / 8 A buck for the M.2 Key-M
slot** (J23). U-Boot negotiates PD on J2 at power-on (PTN5110 at I2C1 0x52, up to 20 V,
15 V preferred). On this board it doesn't: J2 reads **5.05 V**, and the source only advertises
USB default current (`CC_STATUS=0x11`, not 1.5 A/3 A), so the board runs on at most 5 V × 3 A =
15 W, without any guarantee. Check it (the monitor bit is off by default; restore it after):

```bash
i2cget -y 0 0x52 0x1d                    # CC status: 0x11 default, 0x12 1.5 A, 0x13 3 A (CC1)
i2cset -y 0 0x52 0x1c 0x20               # enable the VBUS monitor
echo "J2 VBUS: $(( (($(i2cget -y 0 0x52 0x71) & 3) * 256 + $(i2cget -y 0 0x52 0x70)) * 25 )) mV"
i2cset -y 0 0x52 0x1c 0x60               # back to the default
```

A USB-C-to-USB-C cable with a PD charger that offers 15 V or 20 V should give 15 V here.

**The SSD** (Fanxiang S500PRO 1 TB, PCIe Gen3 x1 here) needs 3.3 V (±5 %) and, from its own
power-state table: 6.5 W ≈ 2.0 A at full speed (PS0), 5.8 W (PS1), 3.6 W ≈ 1.1 A (PS2),
0.75 W asleep. The slot's converter can deliver that; the 5 V input can't, together with
everything else at boot.

**Measured** (resets counted on the console; the PMIC's `PWRON_STAT`,
`i2cget -f -y 0 0x25 0x05`, reads `0x80` after a power-on/brownout reset, `0x20` after a
normal reboot, `0x40` after the watchdog):

| Condition | Result |
|---|---|
| NVMe `fsck` + mount at boot (old setup) | 2–16 brownout resets per 3–5 reboots; Wi-Fi SDIO errors in 1 of 2–4 boots |
| NVMe read at boot (test service, full speed or ~100 MB/s bursts) | reset loop: 16–19 resets in a row |
| NVMe idle at boot (`bbz-nvme-noautomount`) | **0 resets in 15 reboots, 0 Wi-Fi SDIO errors in 10 boots** |
| after boot: 8 GiB write 585 MiB/s, 8 GiB read 455 MiB/s | fine, input stayed 5.0–5.1 V (10 Hz samples) |
| after boot: 8 GiB write + 4 busy CPU cores | brownout reset |

So the SSD works at 5 V input as long as it isn't combined with other heavy load. The Wi-Fi
SDIO timeouts at boot (see the meta-bbz README) disappeared together with the NVMe load and
are most likely the same supply dips, too small to reset the board but enough to upset the
radio's SDIO link. `bbz-image` therefore doesn't mount the NVMe at boot; mount it by hand
(`mount /dev/nvme0n1p1 /mnt`; it has one empty ext4 partition `bbz-nvme`) and avoid full
CPU load during large writes until the board gets proper PD input.
