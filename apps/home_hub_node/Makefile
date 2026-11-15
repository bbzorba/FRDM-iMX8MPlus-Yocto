# Builds home_hub_node for the i.MX 8M Plus (aarch64 Linux).
#
# Normally Yocto builds it: the recipe meta-bbz/recipes-bbz/home-hub-node runs this Makefile
# with the image's cross compiler and ships the binary + service in the image.
# It also works on its own, for quick tries without Yocto:
#
#   make                  build with Ubuntu's aarch64-linux-gnu-gcc into build/
#   make CROSS_COMPILE=   native build (to test on the host)
#   make deploy           copy to the board and restart the service (temporary: the next
#                         image or `make app-deploy` from the repo root replaces it)

CROSS_COMPILE ?= aarch64-linux-gnu-
# A CC=... given on the command line (as the Yocto recipe does) overrides this
CC       := $(CROSS_COMPILE)gcc
CFLAGS   ?= -O2
LDFLAGS  ?=
WARN     := -Wall -Wextra
# Output directory; the Yocto recipe points it outside the source tree
BUILD    ?= build
BOARD    ?= root@imx8mp-lpddr4-frdm.local
# Reflashed images get new host keys, so don't stop on a changed/unknown key
SSH_OPTS ?= -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR

BIN := $(BUILD)/home_hub_node

all: $(BIN)

$(BIN): src/home_hub_node.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) $(WARN) $(LDFLAGS) -o $@ $<

# Same paths as the Yocto package: /usr/bin and /usr/lib/systemd/system
deploy: $(BIN)
	scp $(SSH_OPTS) $(BIN) $(BOARD):/usr/bin/home_hub_node.new
	scp $(SSH_OPTS) home-hub-node.service $(BOARD):/usr/lib/systemd/system/home-hub-node.service
	ssh $(SSH_OPTS) $(BOARD) 'mv /usr/bin/home_hub_node.new /usr/bin/home_hub_node && systemctl daemon-reload && systemctl enable home-hub-node && systemctl restart home-hub-node'

clean:
	rm -rf $(BUILD)

.PHONY: all deploy clean
