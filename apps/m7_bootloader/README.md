# bbz-m7-boot

Bare-metal bootloader for the i.MX 8M Plus Cortex-M7 (no SDK, ~3.7 KB). U-Boot loads
`bbz-m7-boot.bin` from the boot partition into ITCM and starts it before Linux (`preboot`).

- console on UART4 = J19 port 2 (`make m7-console`): `h` help, `s` status,
  `w` stop feeding WDOG3 (M7-only reset test), `j` jump to the application, `RR` reset the board
- MPU first: peripheral space as Device memory (otherwise the M7's speculative reads disturbed
  other masters: U-Boot's eMMC failed, Linux froze)
- WDOG3, 2 s, resets only the M7; boot counter and reset cause survive in the DTCM noinit page
- A53 supervision: heartbeat counter written by Linux (`bbz-m7-heartbeat`) at DTCM `0x2001F000`
  (A53 `0x0081F000`); "heartbeat LOST" after 5 s
- checks a 4 KB pattern in its DDR window `0x80000000` every second (RDC-protected)
- application slot `0x80100000`: header `{magic "BPPA", size, stack, entry}`, started with `j`

Build: `make` (needs `arm-none-eabi-gcc`) → `build/bbz-m7-boot.bin` (bootaux) and
`build/bbz-m7-boot.elf`. In Yocto: `recipes-bsp/bbz-m7-boot`. Details:
[docs/M7_A53_ARCHITECTURE.md](../../docs/M7_A53_ARCHITECTURE.md), sections 5–7.
