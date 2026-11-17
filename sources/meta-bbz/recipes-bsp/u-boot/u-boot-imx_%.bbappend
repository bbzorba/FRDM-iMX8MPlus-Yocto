FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
# led-blink.cfg: software LED blink (`led led-0 blink <ms>`) while `make flash` runs
# m7-rpmsg.cfg:  Cortex-M7 device tree by default + keep the M7's clocks on
SRC_URI += "file://led-blink.cfg file://m7-rpmsg.cfg"
