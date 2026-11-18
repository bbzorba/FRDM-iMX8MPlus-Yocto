FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
# led-blink.cfg: software LED blink (`led led-0 blink <ms>`) while `make flash` runs
# bbz-dtb.cfg:   boot our device tree imx8mp-bbz.dtb by default
# bbz-m7.cfg:    start bbz-m7-boot on the M7 before Linux and lock its memory
# bbz-wdt.cfg:   WDOG1 armed from U-Boot on (60 s), so a frozen SoC resets itself
SRC_URI += "file://led-blink.cfg file://bbz-dtb.cfg file://bbz-m7.cfg file://bbz-wdt.cfg"
