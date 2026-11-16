#!/usr/bin/env python3
"""Drive the board's U-Boot over the J19 serial console (used by `make flash` when the board
isn't reachable over SSH).

  uboot.py [--port /dev/ttyACM0] fastboot   get to the U-Boot prompt (rebooting Linux if needed,
                                            stopping the 2 s autoboot) and start
                                            `fastboot usb 0; reset` (resets once flashing ends)
  uboot.py [--port /dev/ttyACM0] reset      stop fastboot if it is still running, then reset the
                                            board so it boots normally

Needs pyserial (Ubuntu: python3-serial) and access to the port (dialout group).
"""
import argparse, sys, time

try:
    import serial
except ImportError:
    sys.exit("pyserial is missing: sudo apt install python3-serial")

UBOOT_PROMPT = b"=> "


def open_port(port):
    try:
        # exclusive: fail instead of fighting with an open `make console` (picocom)
        return serial.Serial(port, 115200, timeout=0.1, exclusive=True)
    except (serial.SerialException, OSError) as e:
        sys.exit(f"Cannot open {port}: {e}\nIs `make console` still open in another terminal? Close it with Ctrl+A, Ctrl+X.")


def read_until(s, tokens, seconds):
    """Read until one of tokens shows up; return (text, matched token or None)."""
    buf, end = b"", time.time() + seconds
    while time.time() < end:
        buf += s.read(4096)
        for t in tokens:
            if t in buf:
                return buf, t
    return buf, None


def to_uboot_prompt(s):
    """Return once U-Boot's prompt is on the console."""
    s.reset_input_buffer()
    s.write(b"\r")
    out, hit = read_until(s, [UBOOT_PROMPT, b"login:", b"# "], 3)
    if hit == UBOOT_PROMPT:
        print("Board is already at the U-Boot prompt.")
        return
    if hit is None:
        # maybe U-Boot is busy in a previous `fastboot` session: Ctrl+C ends it
        s.write(b"\x03\r")
        out, hit = read_until(s, [UBOOT_PROMPT, b"login:", b"# "], 3)
        if hit == UBOOT_PROMPT:
            print("Stopped a running fastboot session; at the U-Boot prompt.")
            return
    if hit == b"login:":
        s.write(b"root\r")
        out, hit = read_until(s, [b"# "], 5)
    if hit != b"# ":
        sys.exit("No Linux shell or U-Boot prompt on the console. Is the board powered and J19 connected?")
    print("Rebooting Linux to reach U-Boot ...")
    s.write(b"sync; systemctl reboot\r")
    out, hit = read_until(s, [b"Hit any key to stop autoboot"], 120)
    if hit is None:
        sys.exit("U-Boot's autoboot countdown did not appear within 120 s.")
    # any key stops the 2 s countdown
    for _ in range(40):
        s.write(b" ")
        out, hit = read_until(s, [UBOOT_PROMPT], 0.1)
        if hit:
            print("Autoboot stopped; at the U-Boot prompt.")
            return
    sys.exit("Could not stop autoboot.")


def cmd_fastboot(s):
    to_uboot_prompt(s)
    s.reset_input_buffer()
    # LED blinks while flashing; `reset` runs when fastboot ends, booting the new image
    s.write(b"led led-0 blink 250; fastboot usb 0; reset\r")
    # fastboot keeps running (no prompt comes back); an immediate prompt means it failed
    out, hit = read_until(s, [UBOOT_PROMPT], 2)
    text = out.decode(errors="replace").replace("\r", "")
    if hit:
        sys.exit("U-Boot did not start fastboot:\n" + text)
    print("U-Boot fastboot is running on USB-C J3.")


def cmd_reset(s):
    s.reset_input_buffer()
    s.write(b"\r")
    out, hit = read_until(s, [UBOOT_PROMPT], 2)
    if hit is None:
        s.write(b"\x03")          # end fastboot if uuu didn't
        read_until(s, [UBOOT_PROMPT], 3)
    s.write(b"reset\r")
    print("Board reset: booting the new image.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", default="/dev/ttyACM0")
    ap.add_argument("action", choices=["fastboot", "reset"])
    a = ap.parse_args()
    s = open_port(a.port)
    {"fastboot": cmd_fastboot, "reset": cmd_reset}[a.action](s)


if __name__ == "__main__":
    main()
