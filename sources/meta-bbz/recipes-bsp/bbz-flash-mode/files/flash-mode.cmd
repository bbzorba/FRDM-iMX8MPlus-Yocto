# U-Boot script: one-time "flash mode", requested from Linux by `bbz-flash-mode`.
# U-Boot's boot command scans the boot devices for boot.scr (`bootflow scan`) before NXP's
# normal boot, finds this file on the eMMC boot partition (mmc 2:1) and runs it.
echo "bbz: flash mode requested from Linux"
# Delete the trigger FIRST: if anything goes wrong below, the next power-on boots normally.
fatrm mmc 2:1 boot.scr
echo "bbz: fastboot on USB-C J3 - run make flash on the PC - Ctrl+C here cancels"
# LED blinks from now until the board resets into the new image
led led-0 blink 250
fastboot usb 0
# fastboot returns when the PC sends "done" (end of flashing) or after Ctrl+C
reset
