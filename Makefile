# Everyday commands for the FRDM-IMX8MPLUS Yocto build: build, flash, find the board, log in.
# Run `make` (or `make help`) for the list. Variables can be overridden on the command line,
# e.g. `make ssh BOARD=root@192.168.1.50` or `make flash-sd DEV=/dev/sdb`.

TOP       := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
BUILD_DIR ?= build-wayland
MACHINE   ?= imx8mp-lpddr4-frdm
IMAGE     ?= bbz-image
APP       ?= home-hub-node
SERVICE   ?= $(APP)
BOARD     ?= root@$(MACHINE).local
# J19 (CH342F) gives two serial ports: the first is the Cortex-A53 Linux console, the second
# the Cortex-M7's. They usually appear as /dev/ttyACM0/1 (or /dev/ttyUSB0/1 with another driver).
SERIAL    ?= $(or $(firstword $(wildcard /dev/ttyACM0 /dev/ttyUSB0)),/dev/ttyACM0)
DEV       ?=

DEPLOY    := $(TOP)/$(BUILD_DIR)/tmp/deploy/images/$(MACHINE)
DEB_DIR   := $(TOP)/$(BUILD_DIR)/tmp/deploy/deb
BOOT_BIN  := imx-boot-$(MACHINE)-sd.bin-flash_evk
WIC       := $(IMAGE)-$(MACHINE).rootfs.wic.zst
# Every reflash creates new SSH host keys: don't stop on an unknown/changed key
SSH_OPTS  ?= -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=5 \
             -o ServerAliveInterval=2 -o ServerAliveCountMax=3

# Run BitBake from an empty environment, so nothing from ~/.bashrc (e.g. PetaLinux) leaks in
define bitbake
env -i HOME="$(HOME)" USER="$(USER)" TERM="$(TERM)" LANG=C.UTF-8 \
	PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
	bash -c 'cd "$(TOP)" && source setup-environment "$(BUILD_DIR)" >/dev/null && bitbake $(1)'
endef

# Same, with scripts/mem-guard.sh running for the duration of the build
define bitbake_guarded
nice -n 19 "$(TOP)/scripts/mem-guard.sh" & guard=$$!; \
trap 'kill $$guard 2>/dev/null' EXIT; \
$(call bitbake,$(1))
endef

.DEFAULT_GOAL := help

help: ## Show this list
	@echo "FRDM-IMX8MPLUS Yocto: make <target> [VAR=value]"
	@echo
	@grep -hE '^[a-z][a-z-]*:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*## "} {printf "  %-15s %s\n", $$1, $$2}'
	@echo
	@echo "Variables: BOARD=$(BOARD)  IMAGE=$(IMAGE)  APP=$(APP)"
	@echo "           MACHINE=$(MACHINE)  SERIAL=$(SERIAL)  DEV=$(if $(DEV),$(DEV),<unset>)"

# ---------------------------------------------------------------- build
image: ## Build the full image (RAM guard on while building)
	@$(call bitbake_guarded,$(IMAGE))
	@echo; ls -lh $(DEPLOY)/$(WIC) $(DEPLOY)/$(BOOT_BIN)

app: ## Build only the application recipe APP (default home-hub-node)
	@$(call bitbake,$(APP))

status: ## Progress, temperatures, RAM and new errors of the current/last build
	@"$(TOP)/scripts/check-build.sh"

# ---------------------------------------------------------------- board over USB
lsusb: ## List boards waiting in USB serial-download mode (SW5 = 0001)
	@sudo uuu -lsusb

wait-usb: ## Wait until a board appears in serial-download mode (Ctrl+C to stop)
	@echo "Waiting for a board in serial-download mode (SW5 = 0001, USB-C J3 to this PC, power on)..."
	@until sudo uuu -lsusb 2>/dev/null | grep -q 'SDP'; do sleep 2; done
	@sudo uuu -lsusb | grep 'SDP'

# Everyday flashing: no boot-switch change. The image always travels over USB-C J3 (USB, ~MB/s);
# only the "go to fastboot" command takes another path:
#   1. SSH (board running, on the network): `bbz-flash-mode` leaves a one-time U-Boot script on
#      the boot partition and reboots. U-Boot runs it, deletes it, starts fastboot.  -> J3 only.
#   2. otherwise the J19 serial console: scripts/uboot.py stops U-Boot's autoboot and starts
#      fastboot.                                                                 -> J19 + J3.
# uuu then writes image + bootloader (scripts/flash-fastboot.uuu), and U-Boot resets into it.
flash: check-files ## Flash the eMMC over USB-C J3 via U-Boot, no SW5 change (control over SSH, or J19)
	@if ssh $(SSH_OPTS) -o BatchMode=yes $(BOARD) 'command -v bbz-flash-mode' >/dev/null 2>&1; then \
		echo "Board reachable over SSH: rebooting it into U-Boot fastboot..."; \
		timeout 20 ssh $(SSH_OPTS) $(BOARD) bbz-flash-mode 2>/dev/null || true; \
	elif [ -c "$(SERIAL)" ]; then \
		echo "No SSH to $(BOARD): reaching U-Boot over the serial console $(SERIAL)..."; \
		python3 -I "$(TOP)/scripts/uboot.py" --port $(SERIAL) fastboot || exit 1; \
	else \
		echo "Can't reach the board: no SSH to $(BOARD) and no serial console $(SERIAL)."; \
		echo "  Boot it from eMMC (SW5 = 0010) with the network up, or connect J19; or use 'make flash-recovery'."; \
		exit 1; \
	fi
	@echo "Waiting for U-Boot fastboot on USB-C J3 (up to 90 s)..."
	@cd "$(DEPLOY)" && sudo uuu -t 90 -b "$(TOP)/scripts/flash-fastboot.uuu" $(BOOT_BIN) '$(WIC)/*' || { \
		echo; echo "No fastboot device on J3, or flashing failed. Is USB-C J3 connected to this PC?"; \
		echo "  The board waits in fastboot: run 'make flash' again, or power-cycle it (it boots normally)."; \
		exit 1; }
	@echo; echo "Done. The board resets and boots the new image (SW5 unchanged)."

# Recovery: the SoC's Boot ROM, independent of anything on the eMMC (works on an empty or broken one).
flash-recovery: check-files ## Flash via the Boot ROM serial-download mode (SW5 = 0001, USB-C J3)
	@sudo uuu -lsusb 2>/dev/null | grep -q 'SDP' || { \
		echo "No board in serial-download mode."; \
		echo "  Power off, set SW5 = 0001, connect USB-C J3 to this PC, power on, then retry"; \
		echo "  (or run 'make wait-usb' first)."; exit 1; }
	cd "$(DEPLOY)" && sudo uuu -b "$(TOP)/scripts/flash-recovery.uuu" $(BOOT_BIN) '$(WIC)/*'
	@echo; echo "Done. Power off, set SW5 = 0010 (eMMC), power on."

flash-sd: check-files ## Write the image to an SD card: make flash-sd DEV=/dev/sdX
	@test -n "$(DEV)" || { echo "Usage: make flash-sd DEV=/dev/sdX   (whole disk, not a partition)"; exit 1; }
	@test -b "$(DEV)" || { echo "$(DEV) is not a block device"; exit 1; }
	@test "$$(lsblk -dno RM "$(DEV)" | tr -d ' ')" = 1 || { echo "Refusing: $(DEV) is not a removable disk"; exit 1; }
	@test "$$(lsblk -dno TYPE "$(DEV)" | tr -d ' ')" = disk || { echo "Refusing: give the whole disk, e.g. /dev/sdb, not /dev/sdb1"; exit 1; }
	@lsblk -o NAME,SIZE,RM,TRAN,MODEL,MOUNTPOINTS "$(DEV)"
	@read -p "Erase EVERYTHING on $(DEV) and write $(WIC)? [y/N] " a; [ "$$a" = y ] || { echo "Cancelled."; exit 1; }
	@for p in $$(lsblk -lno NAME,MOUNTPOINTS "$(DEV)" | awk 'NF==2{print "/dev/"$$1}'); do sudo umount "$$p"; done
	cd "$(DEPLOY)" && sudo bmaptool copy $(WIC) "$(DEV)"
	@echo; echo "Done. Put the card in the board, set SW5 = 0011 (SD), power on."

check-files:
	@test -f "$(DEPLOY)/$(WIC)" -a -f "$(DEPLOY)/$(BOOT_BIN)" || { \
		echo "Image files not found in $(DEPLOY). Run 'make image' first."; exit 1; }

# ---------------------------------------------------------------- board over the network
find: ## Check whether the board answers on the network (BOARD=...)
	@host=$$(echo "$(BOARD)" | sed 's/.*@//'); \
	if ping -c1 -W2 "$$host" >/dev/null 2>&1; then echo "$$host is up ($$(getent hosts $$host | awk '{print $$1}'))"; \
	else echo "$$host does not answer. Booted from eMMC (SW5 = 0010)? Network up? Try BOARD=root@<ip>"; exit 1; fi

ssh: ## Open a shell on the board
	ssh $(SSH_OPTS) $(BOARD)

monitor: ## Live board status over SSH every 5 s: temps, CPU, RAM, network, APP service
	@ssh $(SSH_OPTS) -t $(BOARD) 'while true; do clear; \
		echo "== $$(hostname)  $$(date "+%F %T")  up $$(cut -d. -f1 /proc/uptime)s  load $$(cut -d" " -f1-3 /proc/loadavg)"; \
		for z in /sys/class/thermal/thermal_zone*; do echo "   $$(cat $$z/type): $$(( $$(cat $$z/temp) / 1000 )) C"; done; \
		free -m | awk "/Mem:/{print \"   RAM used \" \$$3 \" / \" \$$2 \" MB\"}"; \
		df -h / | awk "NR==2{print \"   disk /: \" \$$3 \" used, \" \$$4 \" free\"}"; \
		echo "== network"; nmcli -t -f DEVICE,STATE,CONNECTION device | sed "s/^/   /"; \
		ip -4 -br addr | grep -v "^lo" | sed "s/^/   /"; \
		echo "== $(SERVICE): $$(systemctl is-active $(SERVICE))"; \
		journalctl -u $(SERVICE) -n 3 --no-pager -o cat | sed "s/^/   /"; \
		echo "== failed units: $$(systemctl --failed --no-legend | wc -l)"; \
		sleep 5; done'

logs: ## Follow the APP service's log on the board
	ssh $(SSH_OPTS) -t $(BOARD) journalctl -f -u $(SERVICE)

app-deploy: ## Build APP and install its package on the running board (no reflash)
	@$(call bitbake,$(APP))
	@deb=$$(ls -t $(DEB_DIR)/*/$(APP)_*.deb | head -1); name=$$(basename $$deb); \
	echo "Installing $$name on $(BOARD)"; \
	scp $(SSH_OPTS) "$$deb" $(BOARD):/tmp/ && \
	ssh $(SSH_OPTS) $(BOARD) "dpkg -i /tmp/$$name && rm /tmp/$$name && systemctl restart $(SERVICE) && systemctl --no-pager status $(SERVICE) | head -3"

# Ctrl+C is deliberately passed to the board (to stop programs running there), and Ctrl+D
# only logs out of the board's shell. The console itself closes with Ctrl+A then Ctrl+X.
# Access needs the 'dialout' group, not sudo.
reset: ## Reset the whole board, also when Linux/U-Boot hang (M7 bootloader over J19, else SSH reboot)
	@python3 $(TOP)/scripts/board-reset.py /dev/ttyACM1 $(BOARD)

m7-console: ## Cortex-M7 console on J19 port 2 (bbz-m7-boot: h help, s status, RR reset; exit Ctrl+A Ctrl+X)
	picocom -b 115200 /dev/ttyACM1

console: ## Linux serial console on debug USB-C J19 (exit: Ctrl+A, then Ctrl+X)
	@test -c "$(SERIAL)" || { echo "$(SERIAL) not found: is J19 connected? Ports: $$(ls /dev/ttyACM* /dev/ttyUSB* 2>/dev/null | tr '\n' ' ')"; exit 1; }
	@test -r "$(SERIAL)" -a -w "$(SERIAL)" || { echo "No access to $(SERIAL): add yourself to the dialout group (sudo usermod -aG dialout $$USER), then log in again"; exit 1; }
	@echo "================================================================"
	@echo " Board console on $(SERIAL), 115200 baud. Press Enter for a prompt."
	@echo " EXIT:   press Ctrl+A, release, then press Ctrl+X"
	@echo " Ctrl+C  goes to the board (stops a program there)"
	@echo " Ctrl+D  logs out of the board shell (the console stays open)"
	@echo "================================================================"
	@picocom --quiet -b 115200 $(SERIAL); echo; echo "Console closed."

.PHONY: help image app status lsusb wait-usb flash flash-recovery flash-sd check-files find ssh monitor logs app-deploy console reset m7-console
