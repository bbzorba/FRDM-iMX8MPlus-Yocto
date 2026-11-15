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
| **SoC** i.MX 8M Plus | 4× Cortex-A53 up to 1.8 GHz, **Cortex-M7** 800 MHz, **NPU 2.3 TOPS**, ISP, H.264/H.265 VPU, 3D/2D GPU | A53 runs Linux; NPU/GPU/VPU drivers come with NXP's `imx-image-core`; M7 see below |
| **Tri-radio module** u-blox MAYA-W276-00B (NXP IW612) | **Wi-Fi 6**, **Bluetooth 5.4 / BLE**, **IEEE 802.15.4** (Thread / Zigbee / Matter). On SDIO, UART, SPI, SAI | Wi-Fi: driver `nxp-wlan` + IW612 firmware + NetworkManager (auto-connect: `bbz-wifi-config`). Bluetooth: `bluez5`. 802.15.4/Thread: layers present (`meta-nxp-openthread`, `meta-nxp-matter-baseline`) but no packages in the image yet |
| Memory | 4 GB LPDDR4, 32 GB eMMC 5.1, 32 MB QSPI NOR, microSD 3.0 (J17) | eMMC = boot device |
| **Ethernet** | 2× Gigabit RJ45 (J5, J7), PHY YT8521. ETH1 is a **PoE** powered device, ETH2 supports **TSN** | yes |
| **M.2 Key M 2280** (J23) | PCIe, for an **NVMe SSD** (one is fitted on this board) or an NPU card | PCIe + NVMe drivers in the kernel |
| M.2 Key E 2230 (J24, bottom) | USB, SDIO, SAI, UART, I2C. Wired to the onboard radio by default; using the slot needs resistor rework (see schematic) | — |
| **USB** | USB 3.0 Type-C OTG (J3, also the `uuu` flashing port), USB 3.0 Type-A host (J4) | yes |
| Displays | HDMI 2.0 (J16), MIPI-DSI 4-lane (J15), 2× LVDS 4-lane (J8, J9) with backlight connectors | HDMI with Weston; DSI/LVDS panels via device-tree variants (`imx8mp-frdm-*-panel.dtb`) |
| **Cameras** | 2× MIPI-CSI 4-lane (J13, J14) | via device-tree variants (`imx8mp-frdm-os08a20*.dtb`, `-ap1302-dual.dtb`) |
| **CAN + I2C** (J27, 2x5) | high-speed CAN transceiver TJA1051T/3, I2C expansion, reset and on/off signals; CAN termination DIP switch | CAN via SocketCAN (`can-utils` not in the image yet) |
| **40-pin expansion** (J18) | Raspberry-Pi style: GPIO, I2C, UART, SPI, I2S | `i2c-tools` in the image; GPIO via `/dev/gpiochip*` |
| Audio | WM8962 codec: headphone/mic jack (J21), speaker plug (J22) | ALSA |
| **Debug** USB-C J19 (CH342F) | **two serial ports**: 1st = Cortex-A53 Linux console, 2nd = Cortex-M7 console. JTAG 10-pin (J20) | `make console` |
| Other | RTC with coin-cell connector (P2), fan header, ON/OFF, RESET and USER buttons, RGB LED | |
| Power | USB-C PD input (J2, ≥ 45 W charger), aux DC in (J1), PMIC PCA9450 | |

## Sensors the kernel exposes

Two die temperatures (`cpu-thermal`, `soc-thermal`; trip point 85 °C) and the USB-C PD
controller's voltage/current monitor, which only reads non-zero while the port supplies
power. The board has no environment sensors (add them on the 40-pin header or J27).

## Cortex-M7

The M7 is inside the same SoC, not a separate chip. The default device tree
(`imx8mp-frdm.dtb`) only exposes the HiFi4 DSP to remoteproc. To use the M7 from Linux, boot
`imx8mp-frdm-rpmsg.dtb` (already on the boot partition): in U-Boot,
`setenv fdtfile imx8mp-frdm-rpmsg.dtb; saveenv`. Then load firmware from `/lib/firmware` with
`echo <fw>.elf > /sys/class/remoteproc/remoteprocN/firmware` and `echo start > .../state`, and
talk to it over RPMsg (`/dev/ttyRPMSG*`). Its own console is the second serial port of J19.
Zephyr also supports the M7 directly (board `imx8mp_evk/mimx8ml8/m7`).
