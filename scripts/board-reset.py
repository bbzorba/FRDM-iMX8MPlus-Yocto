#!/usr/bin/env python3
"""Reset the whole board from the PC, also when Linux or U-Boot hangs.

Sends "RR" to bbz-m7-boot on the M7 console (J19 second port, /dev/ttyACM1); the M7 resets the
SoC through WDOG3. The board has no reset line the PC could drive (J19's RTS/DTR are not
connected). Falls back to `systemctl reboot` over SSH if the M7 does not answer.
"""
import subprocess, sys, time
try:
    import serial
except ImportError:
    sys.exit("needs python3-serial")

port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyACM1"
board = sys.argv[2] if len(sys.argv) > 2 else "root@imx8mp-lpddr4-frdm.local"
try:
    m = serial.Serial(port, 115200, timeout=0.2)
    m.reset_input_buffer()
    m.write(b"R"); time.sleep(0.3); m.write(b"R")
    reply, end = b"", time.time() + 2
    while time.time() < end and b"resetting the whole board" not in reply:
        reply += m.read(256)
    if b"resetting the whole board" in reply:
        print("M7: resetting the whole board (WDOG3). Linux is back in ~30 s.")
        sys.exit(0)
    print("no answer from bbz-m7-boot on", port)
except serial.SerialException as e:
    print("cannot use", port, "-", e)
print("trying reboot over SSH ...")
r = subprocess.run(["timeout", "15", "ssh", "-o", "ConnectTimeout=8", board, "systemctl --no-block reboot"])
sys.exit(0 if r.returncode == 0 else 1)
