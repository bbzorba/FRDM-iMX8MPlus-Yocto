/* bbz M7 bootloader: code in ITCM, data/stack in DTCM (i.MX 8M Plus Cortex-M7 view). */
ENTRY(reset_handler)
MEMORY {
    ITCM (rx)  : ORIGIN = 0x00000000, LENGTH = 128K
    DTCM (rw)  : ORIGIN = 0x20000000, LENGTH = 0x1F000   /* 124 KB data + stack */
    SHARED (rw): ORIGIN = 0x2001F000, LENGTH = 0x1000    /* 4 KB: A53 heartbeat, boot info (noinit) */
}
_stack_top = ORIGIN(DTCM) + LENGTH(DTCM);
SECTIONS {
    .vectors : { KEEP(*(.vectors)) } > ITCM
    .text    : { *(.text*) *(.rodata*) . = ALIGN(4); } > ITCM
    .data    : { _data_start = .; *(.data*) . = ALIGN(4); _data_end = .; } > DTCM AT > ITCM
    _data_load = LOADADDR(.data);
    .bss (NOLOAD) : { _bss_start = .; *(.bss*) *(COMMON) . = ALIGN(4); _bss_end = .; } > DTCM
    .shared (NOLOAD) : { KEEP(*(.shared)) } > SHARED
    /DISCARD/ : { *(.ARM.exidx*) *(.comment) }
}
