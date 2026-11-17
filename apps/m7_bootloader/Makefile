# bbz-m7-boot: bare-metal bootloader for the i.MX 8M Plus Cortex-M7.
# make            -> build/bbz-m7-boot.elf (remoteproc) + build/bbz-m7-boot.bin (U-Boot bootaux)
CROSS ?= arm-none-eabi-
BUILD ?= build
# M7_* are always used; CFLAGS/LDFLAGS may add to them (Yocto passes them empty)
M7_CFLAGS = -mcpu=cortex-m7 -mthumb -Os -ffreestanding -fno-builtin -Wall -Wextra -g
M7_LDFLAGS = -nostdlib -nostartfiles -T m7.ld -Wl,--gc-sections -Wl,-Map=$(BUILD)/bbz-m7-boot.map

all: $(BUILD)/bbz-m7-boot.bin

$(BUILD)/bbz-m7-boot.elf: main.c m7.ld | $(BUILD)
	$(CROSS)gcc $(M7_CFLAGS) $(CFLAGS) $(M7_LDFLAGS) $(LDFLAGS) -o $@ main.c -lgcc

$(BUILD)/bbz-m7-boot.bin: $(BUILD)/bbz-m7-boot.elf
	$(CROSS)objcopy -O binary -j .vectors -j .text -j .data $< $@

$(BUILD):
	mkdir -p $@

clean:
	rm -rf $(BUILD)
.PHONY: all clean
