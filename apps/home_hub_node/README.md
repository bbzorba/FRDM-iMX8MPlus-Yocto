# home_hub_node: developer manual

Client node for the ESP32-C6 Home Hub
([ESP32C6-Zephyr-IoT-Projects](https://github.com/bbzorba/ESP32C6-Zephyr-IoT-Projects),
`applications/web_app`). It runs on the FRDM-IMX8MPLUS under the Yocto image and POSTs the
board's readings to `http://home-hub/api/node` every 10 s. systemd starts it at boot.

| Document | Content |
|---|---|
| this README | build, deploy, configure, test |
| [ARCHITECTURE.md](ARCHITECTURE.md) | where it runs, main loop, data sources |
| [../../docs/README.md](../../docs/README.md) | the board: modules, sensors, Cortex-M7, vendor manuals |

```
apps/home_hub_node/
├── src/home_hub_node.c       the program (C, glibc only)
├── home-hub-node.service     systemd unit, installed to /usr/lib/systemd/system/
├── Makefile                  used by the Yocto recipe, and for manual builds
├── README.md                 this file
└── ARCHITECTURE.md
sources/meta-bbz/recipes-bbz/home-hub-node/home-hub-node_1.0.bb   the Yocto recipe
```

## 1. How it gets onto the board

The app is part of the image. `home-hub-node_1.0.bb` builds **this folder in place**
(`externalsrc`): it runs the Makefile here with Yocto's cross compiler and flags, then installs
`/usr/bin/home_hub_node` and the service, which is enabled at boot. BitBake checksums the files
in this folder, so **any change here is rebuilt automatically** on the next build. There's no
version to bump and nothing to copy.

All commands below run from the repository root.

| Goal | Command | Time |
|---|---|---|
| Change the code and try it on the running board | `make app-deploy` | ~1 min: builds the package and installs it over SSH (`dpkg -i`, then a service restart) |
| Put it in a new image | `make image`, then `make flash` | image ~6 min after a code change, flash ~4 min |
| Build only (check that it compiles) | `make app` | |
| Follow its log | `make logs` | |

`BOARD` defaults to `root@imx8mp-lpddr4-frdm.local`: the image runs avahi, so no IP is needed.
Override it with `make app-deploy BOARD=root@<ip>`. The board's root user has no password, so
SSH needs no key setup.

### Without Yocto (quick experiments)
The Makefile here also builds on its own. It uses Ubuntu's cross compiler
(`sudo apt install gcc-aarch64-linux-gnu`; under Windows, the same inside WSL):

```bash
cd apps/home_hub_node
make                    # cross-compile into build/ (git-ignored)
make CROSS_COMPILE=     # native build, to test on the PC
make deploy             # scp to /usr/bin on the board + restart the service
```

`make deploy` overwrites the packaged binary until the next `make app-deploy` or reflash. The
board runs glibc 2.43, and the binary only needs glibc, so any older cross compiler works. For an
exact toolchain match, build the image SDK (`bitbake bbz-image -c populate_sdk`), source its
`environment-setup-*` script and run `make CC="$CC"`.

## 2. Configuration

Environment variables, set in `home-hub-node.service`:

| Variable | Default | Meaning |
|---|---|---|
| `HUB_HOST` | `home-hub` (resolved by the router) | hub name or IP |
| `HUB_PORT` | `80` | hub HTTP port |

The node id is the board's **hostname**, `imx8mp-lpddr4-frdm` in this image. NXP's demo image
used `imx8mpevk`, so the hub shows a new card after the switch. To keep a fixed name, set
`hostname:pn-base-files = "<name>"` in `build-wayland/conf/local.conf`.

## 3. Testing

| Test | How | Expected |
|---|---|---|
| Running | `make monitor`, or `ssh root@<board> systemctl status home-hub-node` | `active (running)` |
| Reports arrive | open the hub's dashboard | an `imx8mp-lpddr4-frdm` card, values change every 10 s |
| Hub down | unplug the hub; `make logs` | `Hub not reachable, stopping until it is back` |
| Hub back | plug the hub in again | `Hub reachable again, resuming` within 60 s |
| Autostart | `reboot` the board without logging in | card back online after boot |
| Fresh image | `make flash`, boot | runs without any manual deploy |

## 4. Report format

```json
{"id":"imx8mp-lpddr4-frdm","model":"NXP FRDM-IMX8MPLUS",
 "sensors":[{"name":"CPU temperature","value":46.00,"unit":"°C"}]}
```

At most 16 sensors, names ≤ 23 bytes and units ≤ 7 bytes (the hub's limits). Raw UTF-8, no
`\uXXXX` escapes.
