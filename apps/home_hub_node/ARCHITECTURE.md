# home_hub_node architecture

How the i.MX 8M Plus reports to the ESP32-C6 Home Hub. Build, deploy and test:
[README.md](README.md). Hub side:
[ESP32C6-Zephyr-IoT-Projects/applications/web_app](https://github.com/bbzorba/ESP32C6-Zephyr-IoT-Projects/tree/main/applications/web_app).

## 1. Where it runs

```
+------------------------- i.MX 8M Plus SoC --------------------------+
|                                                                      |
|  4x Cortex-A53, Linux (Yocto)                     Cortex-M7 (unused) |
|  +-------------------------------------------+                       |
|  |  systemd: home-hub-node.service           |                       |
|  |        | starts at boot, restarts on crash|                       |
|  |        v                                  |                       |
|  |  home_hub_node  (1 process, 1 thread)     |                       |
|  |        ^ sysfs / procfs                   |                       |
|  |  kernel: thermal, hwmon, cpufreq, /proc   |<-- 2x die temp sensors|
|  +-------------------------------------------+                       |
+----------------------------------------------------------^-----------+
                                                           |
                                         USB-C PD controller (V/I monitor)

home_hub_node --- HTTP POST /api/node (TCP, every 10 s) ---> ESP32-C6 hub
```

| Part | Job |
|---|---|
| systemd unit | starts the node at boot (after `network-online.target`), restarts it 10 s after a crash |
| `home_hub_node` | reads sensors through sysfs/procfs, builds the JSON report, POSTs it |
| kernel drivers | thermal zones (CPU, SoC), hwmon (USB-C PD), cpufreq, `/proc/stat`, `/proc/meminfo` |
| Cortex-M7 | not used ([docs/README.md](../../docs/README.md#cortex-m7)) |

Plain glibc sockets and file reads: no kernel module, no extra library.

## 2. Main loop

```
start
  |
  v
read sensors, build JSON (<= 16 values) <------------------+
  |                                                         |
  v                                                         |
TCP connect + POST ok? --yes--> sleep the rest of 10 s -----+
  | no                                     ^
  v                                        |
hub down: send nothing                     |
  |                                        |
  v                                        |
sleep 60 s, TCP connect only --works?--yes-+
  ^              | no
  +--------------+
```

| Timing | Value | Where |
|---|---|---|
| Report period | 10 s on `CLOCK_MONOTONIC` | `PERIOD_S` |
| Retry while the hub is down | 60 s, connect only | `RETRY_S` |
| Connect / send / receive timeout | 5 s | `TIMEOUT_S` |
| Hub marks the node offline | 30 s without a report | hub, `nodes.c` |

## 3. Data sources

| Value | Source |
|---|---|
| CPU / SoC temperature | `/sys/class/thermal/thermal_zone*/temp` (m°C → °C) |
| USB-C V / A | `/sys/class/hwmon/*/in0_input`, `curr1_input` (only when non-zero) |
| CPU load | `/proc/stat`, delta since the last report |
| CPU frequency | `cpufreq/scaling_cur_freq`, average over all cores |
| Load avg, memory, disk, uptime | `/proc/loadavg`, `/proc/meminfo`, `statvfs("/")`, `/proc/uptime` |

Node id = hostname, model = `/proc/device-tree/model`.

## 4. Design points

- **Stateless towards the hub:** one TCP connection per report, nothing buffered while the hub
  is away.
- **Back off when the hub is down:** only a bare connect every 60 s.
- **In the image:** the Yocto recipe `home-hub-node_1.0.bb` installs the binary and the enabled
  unit, so a freshly flashed board reports without a manual step.
