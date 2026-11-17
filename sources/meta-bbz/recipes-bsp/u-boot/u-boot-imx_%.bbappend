FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
# led-blink.cfg: software LED blink (`led led-0 blink <ms>`) while `make flash` runs
# bbz-dtb.cfg:   boot our device tree imx8mp-bbz.dtb by default, kernel arg for the M7 clocks
SRC_URI += "file://led-blink.cfg file://bbz-dtb.cfg"
