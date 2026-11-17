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
| **M.2 Key M 2280** (J23) | PCIe, for an **NVMe SSD** (one is fitted on this board) or an NPU card | PCIe + NVMe drivers; **not mounted at boot** (see below): `mount /dev/nvme0n1p2 /mnt` |
| M.2 Key E 2230 (J24, bottom) | USB, SDIO, SAI, UART, I2C. Wired to the onboard radio by default; using the slot needs resistor rework (see schematic) | — |
| **USB** | USB 3.0 Type-C OTG (J3, also the `uuu` flashing port), USB 3.0 Type-A host (J4) | yes |
| Displays | HDMI 2.0 (J16), MIPI-DSI 4-lane (J15), 2× LVDS 4-lane (J8, J9) with backlight connectors | HDMI with Weston; DSI/LVDS panels via device-tree variants (`imx8mp-frdm-*-panel.dtb`) |
| **Cameras** | 2× MIPI-CSI 4-lane (J13, J14) | via device-tree variants (`imx8mp-frdm-os08a20*.dtb`, `-ap1302-dual.dtb`) |
| **CAN + I2C** (J27, 2x5) | high-speed CAN transceiver TJA1051T/3, I2C expansion, reset and on/off signals; CAN termination DIP switch | owned by the **M7** in `imx8mp-bbz.dtb`; for SocketCAN give FlexCAN1 back to Linux (`can-utils` not in the image yet) |
| **40-pin expansion** (J18) | Raspberry-Pi style: GPIO, I2C, UART, SPI, I2S | `i2c-tools` in the image; GPIO via `/dev/gpiochip*` |
| Audio | WM8962 codec: headphone/mic jack (J21), speaker plug (J22) | ALSA |
| **Debug** USB-C J19 (CH342F) | **two serial ports**: 1st = Cortex-A53 Linux console, 2nd = Cortex-M7 console. JTAG 10-pin (J20) | `make console`. On the PC they appear as `/dev/ttyACM0` (Linux) and `/dev/ttyACM1` (M7) |
| Other | RTC with coin-cell connector (P2), fan header, ON/OFF, RESET and USER buttons, RGB LED | |
| Power | USB-C PD input (J2, ≥ 45 W charger), aux DC in (J1), PMIC PCA9450 | |

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

## Boot resets caused by the NVMe SSD

With the 1 TB NVMe SSD (Fanxiang S500PRO) mounted automatically at boot, the board often reset
itself 6–10 s into the kernel, sometimes several times in a row, and a poweroff could turn into
a restart. The PMIC's power-on status (`i2cget -f -y 0 0x25 0x05`) then read `0x80`
(power-on) instead of `0x20` (software reset): the board's supply browned out. The trigger was
`fsck` + mount of the SSD running at the same time as the rest of the boot (all cores, eMMC,
Wi-Fi firmware download). Measured: 2–16 extra resets per 3–5 reboots with the automount, with
any device tree, kernel arguments, and with Wi-Fi off; 0 in 5 without it; the same `fsck` +
mount after boot, and 20 s of full-speed reads, didn't reset the board. So `bbz-image` no
longer mounts the NVMe at boot (`bbz-nvme-noautomount`): 0 extra resets in 5 reboots, and
boot to login takes ~30 s instead of ~60 s. Mount it by hand when needed. The
real cause is electrical (peak current of the SSD on top of the boot load): try another
charger or SSD, or measure the 3.3 V rail of J23, if the NVMe has to be mounted at boot.

