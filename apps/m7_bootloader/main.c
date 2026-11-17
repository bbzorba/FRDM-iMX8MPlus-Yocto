/*
 * bbz-m7-boot: minimal bare-metal bootloader for the i.MX 8M Plus Cortex-M7 (no SDK).
 *
 * Started by U-Boot (bootaux, before Linux) or by Linux remoteproc. Runs from ITCM/DTCM only.
 *  - console on UART4 (J19 second port, 115200 8N1); commands: h s w j
 *  - arms WDOG3, the M7-only watchdog (SRC_M7RCR.WDOG3_RST_OPTION = 0: resets only the M7)
 *  - supervises Linux on the A53s: heartbeat word at DTCM 0x2001F000 (A53 address 0x0081F000)
 *  - keeps a pattern in its DDR region 0x80000000 (RDC-protected from the A53s) and checks it
 *  - application slot at 0x80100000 (future application): jumps there only on command 'j'
 */
#include <stdint.h>

#define REG32(a) (*(volatile uint32_t *)(a))
#define REG16(a) (*(volatile uint16_t *)(a))

/* Peripherals (M7 and A53 see them at the same addresses) */
#define IOMUXC      0x30330000u
#define CCM         0x30380000u
#define SRC         0x30390000u
#define UART4       0x30A60000u
#define WDOG3       0x302A0000u

#define SRC_A53RCR0 (SRC + 0x04)
#define SRC_M7RCR   (SRC + 0x0C)

#define DDR_REGION  0x80000000u          /* m7_reserved in imx8mp-bbz.dts, 16 MB */
#define APP_SLOT    0x80100000u          /* application image header */
#define APP_MAGIC   0x41505042u          /* "BPPA" */
#define PATTERN_WORDS 1024u              /* 4 KB test pattern at DDR_REGION */

struct shared {                          /* at 0x2001F000 (A53: 0x0081F000) */
    volatile uint32_t a53_heartbeat;     /* written by Linux (bbz-m7-heartbeat) every second */
    volatile uint32_t magic;             /* boot info survives M7-only resets (noinit) */
    volatile uint32_t boot_count;
    volatile uint32_t wdog_test;         /* set before a deliberate WDOG3 timeout */
    volatile uint32_t m7_uptime_s;       /* for Linux to read */
    volatile uint32_t ddr_errors;
};
__attribute__((section(".shared"))) static struct shared sh;
#define SHARED_MAGIC 0xB0B0CAFEu

extern uint32_t _stack_top, _data_start, _data_end, _data_load, _bss_start, _bss_end;
static uint32_t core_hz;
static int ddr_on = 1;                   /* periodic DDR pattern check (command d) */

/* ---------- clocks / pins / UART4 ---------- */
static void ccm_root_on(uint32_t target_off, uint32_t gate_off)
{
    REG32(CCM + target_off + 4) = 1u << 28;      /* TARGET_ROOT_SET: enable the root (keep mux/div) */
    REG32(CCM + gate_off + 4) = 3u << 4;         /* CCGR_SET: domain 1 (M7) "always needed" */
}

static void uart_init(void)
{
    /* UART4 root: ask for osc_24m /1, then use whatever the root really runs at (the write
     * can be refused, e.g. U-Boot leaves it at osc_24m /2); gate on for the M7 domain */
    REG32(CCM + 0xb080) = 1u << 28;
    REG32(CCM + 0x44c0 + 4) = 3u << 4;
    static const uint32_t uart_src[8] = { 24000000, 80000000, 200000000, 100000000, 0, 0, 0, 0 };
    uint32_t r = REG32(CCM + 0xb080);
    uint32_t ref = uart_src[(r >> 24) & 7];
    if (!ref) ref = 24000000;
    ref /= (((r >> 16) & 7) + 1) * ((r & 0x3f) + 1);
    /* pads UART4_RXD / UART4_TXD -> ALT0, RX daisy chain */
    REG32(IOMUXC + 0x238) = 0; REG32(IOMUXC + 0x498) = 0x140;
    REG32(IOMUXC + 0x23C) = 0; REG32(IOMUXC + 0x49C) = 0x140;
    REG32(IOMUXC + 0x600) = 0x8;
    REG32(UART4 + 0x80) = 0;                     /* UCR1: disable */
    REG32(UART4 + 0x84) = 0;                     /* UCR2: reset */
    while (!(REG32(UART4 + 0x84) & 1)) ;         /* wait for SRST release */
    REG32(UART4 + 0x84) = (1u<<14) | (1u<<5) | (1u<<2) | (1u<<1) | 1u; /* IRTS, 8 bit, TXEN, RXEN, SRST */
    REG32(UART4 + 0x88) = (1u<<2);               /* UCR3: RXDMUXSEL */
    REG32(UART4 + 0x90) = (5u<<7) | (2u<<10) | 1u; /* UFCR: refclk/1, TXTL 2, RXTL 1 */
    REG32(UART4 + 0xA4) = 15;                    /* UBIR */
    REG32(UART4 + 0xA8) = ref / 115200u - 1;     /* UBMR (UBIR 15): baud = ref / (UBMR + 1) */
    REG32(UART4 + 0x80) = 1;                     /* UCR1: UARTEN */
}
static void putc_(char c) { while (REG32(UART4 + 0xB4) & (1u<<4)) ; REG32(UART4 + 0x40) = (uint8_t)c; }
static void puts_(const char *s) { while (*s) { if (*s == '\n') putc_('\r'); putc_(*s++); } }
static int getc_nb(void) { return (REG32(UART4 + 0x98) & 1) ? (int)(REG32(UART4) & 0xff) : -1; }
static void putu(uint32_t v) { char b[11]; int i = 10; b[i] = 0; do { b[--i] = '0' + v % 10; v /= 10; } while (v); puts_(&b[i]); }
static void putx(uint32_t v) { puts_("0x"); for (int i = 28; i >= 0; i -= 4) putc_("0123456789abcdef"[(v >> i) & 15]); }

/* ---------- time: DWT cycle counter ---------- */
static uint32_t m7_clock_hz(void)
{
    static const uint32_t src[8] = { 24000000, 200000000, 250000000, 0, 800000000, 0, 0, 0 };
    uint32_t r = REG32(CCM + 0x8080);
    uint32_t hz = src[(r >> 24) & 7];
    return hz ? hz / (((r >> 0) & 7) + 1) : 800000000u;
}
static uint64_t ticks_hi; static uint32_t last_cyc;
static uint64_t now_cycles(void)
{
    uint32_t c = REG32(0xE0001004);
    if (c < last_cyc) ticks_hi += 1ull << 32;
    last_cyc = c;
    return ticks_hi | c;
}
static uint32_t now_ms(void) { return (uint32_t)(now_cycles() / (core_hz / 1000)); }

/* ---------- WDOG3: M7-only watchdog ---------- */
static void wdog3_start(uint32_t timeout_halfsec)
{
    ccm_root_on(0xb900, 0x4550);                 /* "wdog" root + wdog3 gate for the M7 domain */
    REG16(WDOG3 + 0x8) = 0;                      /* WMCR: disable the power-down counter */
    /* WT | SRS (no software reset) | WDA (no WDOG_B pin) | WDE; keeps counting in low-power modes */
    REG16(WDOG3 + 0x0) = (uint16_t)(((timeout_halfsec - 1) << 8) | (1u<<5) | (1u<<4) | (1u<<2));
}
static void wdog3_kick(void) { REG16(WDOG3 + 0x2) = 0x5555; REG16(WDOG3 + 0x2) = 0xAAAA; }

/* ---------- protected DDR region ---------- */
static uint32_t pattern(uint32_t i) { return 0x5A5A0000u ^ (i * 2654435761u); }
static void ddr_fill(void) { for (uint32_t i = 0; i < PATTERN_WORDS; i++) REG32(DDR_REGION + 4*i) = pattern(i); }
static uint32_t ddr_check(void)
{
    uint32_t bad = 0;
    for (uint32_t i = 0; i < PATTERN_WORDS; i++) if (REG32(DDR_REGION + 4*i) != pattern(i)) bad++;
    return bad;
}

/* ---------- application slot ---------- */
struct app_header { uint32_t magic, size, stack, entry; };
static int app_valid(void)
{
    const struct app_header *h = (const struct app_header *)APP_SLOT;
    return h->magic == APP_MAGIC && h->entry >= APP_SLOT && h->entry < DDR_REGION + 0x1000000u;
}
static void app_jump(void)
{
    const struct app_header *h = (const struct app_header *)APP_SLOT;
    puts_("jumping to application at "); putx(h->entry); puts_("\n");
    __asm volatile ("msr msp, %0\n bx %1" :: "r"(h->stack), "r"(h->entry | 1));
}

/*
 * Remote board reset (J19 port 2 is the M7 console, usable while Linux or U-Boot hangs):
 * switch WDOG3 to global reset (SRC_M7RCR.WDOG3_RST_OPTION = 1) and let it expire.
 */
static void board_reset(void)
{
    puts_("resetting the whole board (WDOG3, global reset)\n");
    while (!(REG32(UART4 + 0x98) & (1u<<3))) ;                   /* USR2.TXDC: sent */
    REG32(SRC_M7RCR) |= (1u<<9) | (1u<<8);                      /* global reset */
    ccm_root_on(0xb900, 0x4550);
    REG16(WDOG3 + 0x8) = 0;
    REG16(WDOG3 + 0x0) = (uint16_t)((0u << 8) | (1u<<5) | (1u<<4) | (1u<<2)); /* 0.5 s */
    for (;;) ;
}

static void status(uint32_t a53_alive, uint32_t hb)
{
    puts_("t="); putu(now_ms() / 1000); puts_("s A53="); puts_(a53_alive ? "alive" : "no heartbeat");
    puts_(" hb="); putu(hb); puts_(" DDR="); puts_(sh.ddr_errors ? "CORRUPTED" : "ok");
    puts_(ddr_on ? "" : " (DDR checks off)"); puts_(" WDOG3=on app="); puts_(app_valid() ? "valid" : "none"); puts_("\n");
}

int main(void)
{
    REG32(0xE000EDFC) |= 1u << 24;               /* DEMCR.TRCENA */
    REG32(0xE0001FB0) = 0xC5ACCE55;              /* DWT unlock */
    REG32(0xE0001000) |= 1;                      /* DWT cycle counter on */
    core_hz = m7_clock_hz();
    uart_init();

    uint32_t wrsr = REG16(WDOG3 + 0x4);
    int first = sh.magic != SHARED_MAGIC;
    if (first) { sh.magic = SHARED_MAGIC; sh.boot_count = 0; sh.wdog_test = 0; sh.a53_heartbeat = 0; }
    sh.boot_count++;

    puts_("\n\n=== bbz-m7-boot v1 (Cortex-M7, ITCM) ===\nboot #"); putu(sh.boot_count);
    puts_(first ? "  (first start since power-on or load)" :
          (wrsr & 2) || sh.wdog_test ? "  (M7 reset by WDOG3 timeout; Linux untouched)" : "  (M7 restarted)");
    puts_("\ncore clock "); putu(core_hz / 1000000); puts_(" MHz, UART4 root "); putx(REG32(CCM + 0xb080)); puts_(", SRC_M7RCR="); putx(REG32(SRC_M7RCR));
    puts_(" SRC_A53RCR0="); putx(REG32(SRC_A53RCR0)); puts_("\n");
    sh.wdog_test = 0;

#ifndef NO_WDOG
    wdog3_start(4);                              /* 2 s */
#endif
#ifndef NO_DDR
    if (first || ddr_check()) ddr_fill();
#endif
    sh.ddr_errors = 0;
    puts_("WDOG3 armed (2 s, M7-only reset); DDR pattern at "); putx(DDR_REGION);
    puts_("; app slot "); putx(APP_SLOT); puts_(app_valid() ? " valid" : " empty"); puts_("\n");
    puts_("commands: h help, s status, w stop feeding WDOG3, j jump to app, RR reset board\n");

    uint32_t last_hb = sh.a53_heartbeat, hb_ms = now_ms(), next = now_ms() + 1000, ticks = 0;
    uint32_t last_poll = now_ms(), last_kick = now_ms(), reset_armed = 0x80000000u;
    int a53_alive = 0, feed = 1;
    for (;;) {
        /* Wait on the core's own cycle counter (private bus). Polling UART4/WDOG3 in a tight
         * loop floods the shared peripheral bus: U-Boot's eMMC accesses then timed out. */
        uint32_t now = now_ms();
        if (now - last_poll < 1) continue;
        last_poll = now;
#ifndef NO_WDOG
        if (feed && now - last_kick >= 100) { wdog3_kick(); last_kick = now; }
#endif
        int c = getc_nb();
        if (c == 'h') puts_("h help, s status, w stop feeding WDOG3 (M7 resets in 2 s), j jump to app, RR reset the board\n");
        else if (c == 's') status(a53_alive, last_hb);
        else if (c == 'd') { ddr_on = !ddr_on; puts_(ddr_on ? "DDR checks on\n" : "DDR checks off\n"); }
        else if (c == 'w') { puts_("not feeding WDOG3 any more\n"); sh.wdog_test = 1; feed = 0; }
        else if (c == 'j') { if (app_valid()) app_jump(); else puts_("no valid application\n"); }
        else if (c == 'R') {
            if (now - reset_armed < 2000) board_reset();
            reset_armed = now; puts_("send R again within 2 s to reset the whole board\n");
        }

        uint32_t t = now_ms();
        if ((int32_t)(t - next) >= 0) {
            next += 1000; ticks++;
            sh.m7_uptime_s = t / 1000;
            uint32_t hb = sh.a53_heartbeat;
            if (hb != last_hb) { last_hb = hb; hb_ms = t; if (!a53_alive) { a53_alive = 1; puts_("A53 heartbeat: Linux alive\n"); } }
            else if (a53_alive && t - hb_ms > 5000) { a53_alive = 0; puts_("A53 heartbeat LOST (Linux hung or stopped); M7 keeps running\n"); }
#ifndef NO_DDR
            uint32_t bad = ddr_on ? ddr_check() : sh.ddr_errors;
#else
            uint32_t bad = 0;
#endif
            if (bad && !sh.ddr_errors) { puts_("DDR region CORRUPTED: "); putu(bad); puts_(" words changed\n"); }
            sh.ddr_errors = bad;
            if (ticks % 5 == 0) status(a53_alive, last_hb);
        }
    }
}

/* ---------- startup ---------- */
/*
 * MPU: without it the Cortex-M7 treats 0x30000000-0x3FFFFFFF (all i.MX peripherals) as normal
 * memory and may read it speculatively and in wider bursts. On this board that popped the eMMC
 * controller's FIFO under U-Boot and froze the SoC under Linux (peripherals with gated clocks).
 */
static void mpu_init(void)
{
    __asm volatile ("dsb");
    REG32(0xE000ED94) = 0;                                   /* MPU off while configuring */
    REG32(0xE000ED98) = 0; REG32(0xE000ED9C) = 0x30000000;   /* region 0: 256 MB peripherals */
    REG32(0xE000EDA0) = (1u<<28) | (3u<<24) | (1u<<16) | (27u<<1) | 1u;  /* XN, RW, device */
    REG32(0xE000ED98) = 1; REG32(0xE000ED9C) = 0x80000000;   /* region 1: 16 MB DDR window */
    REG32(0xE000EDA0) = (3u<<24) | (1u<<19) | (23u<<1) | 1u; /* RW, normal non-cacheable */
    REG32(0xE000ED94) = (1u<<2) | 1u;                        /* PRIVDEFENA | ENABLE */
    __asm volatile ("dsb\n isb");
}

void reset_handler(void)
{
    mpu_init();
    uint32_t *s = &_data_load, *d = &_data_start;
    while (d < &_data_end) *d++ = *s++;
    for (d = &_bss_start; d < &_bss_end; ) *d++ = 0;
    main();
    for (;;) ;
}
void fault_handler(void)
{
    puts_("\nM7 FAULT: CFSR="); putx(REG32(0xE000ED28)); puts_(" BFAR="); putx(REG32(0xE000ED38)); puts_("\n");
    for (;;) ;                                   /* WDOG3 resets the M7 */
}
__attribute__((section(".vectors"), used)) static const void *vectors[16] = {
    &_stack_top, (void *)reset_handler, (void *)fault_handler, (void *)fault_handler,
    (void *)fault_handler, (void *)fault_handler, (void *)fault_handler,
};
