# ISA example cards

Example expansion cards that plug into k8086 via the [`k8086-api`](../k8086-api/) SPI.

## Build a card JAR

```bash
./gradlew :cards:adlib:jar
# → cards/adlib/build/libs/adlib-1.0-SNAPSHOT.jar
```

Each card module `compileOnly` depends on `:k8086-api` and registers a factory:

```
META-INF/services/com.trugath.k8086.api.IsaCardFactory
→ fully.qualified.FactoryClass
```

## Load at runtime

Single-instance CLI:

```bash
./gradlew :k8086-emulator:run --args="disks/fd.img --card cards/adlib/build/libs/adlib-1.0-SNAPSHOT.jar,base=0x388"
```

`--card path.jar` or `--card=path.jar,key=val,...` may be repeated. Config keys are
card-specific strings passed to `IsaCardFactory.create`.

Run with **no arguments** (`./gradlew :k8086-emulator:run`) to open the Swing start wizard,
or use the workstation New-VM flow (`./gradlew :k8086-app:run`). Both expose card plugins via
`IsaCardFactory.descriptor()` and `resourceClaims(config)` so IRQ/DMA/I/O fields can be edited
and collisions detected before boot.

## Included examples

| Module | Id | Role |
|--------|-----|------|
| `sample-rom` | `com.trugath.k8086.cards.sample-rom` | Option ROM + scratch I/O port |
| `ram-umb` | `com.trugath.k8086.cards.ram-umb` | Upper memory block RAM (UMB only) |
| `mem-expansion` | `com.trugath.k8086.cards.mem-expansion` | Conventional fill to 640K + UMB (1MB-class board) |
| `uart-8250` | `com.trugath.k8086.cards.uart-8250` | NS8250 serial (default COM2 @ 0x2F8 IRQ3) |
| `rtc-mm58167` | `com.trugath.k8086.cards.rtc-mm58167` | SixPak-style RTC @ 0x2C0 + INT 1Ah option ROM |
| `lpt` | `com.trugath.k8086.cards.lpt` | Parallel port (default LPT2 @ 0x278) |
| `gameport` | `com.trugath.k8086.cards.gameport` | Game adapter stub @ 0x201 |
| `adlib` | `com.trugath.k8086.cards.adlib` | AdLib OPL ports + square-wave audio (not full FM) |
| `heartbeat` | `com.trugath.k8086.cards.heartbeat` | Periodic IRQ heartbeat |
| `ems-window` | `com.trugath.k8086.cards.ems-window` | Page-frame EMS window |
| `de220` | `com.trugath.k8086.cards.de220` | D-Link DE-220 NE2000 NIC (virtual NAT network) |

### 5155 + 1MB memory + SixPak-style I/O

Motherboard **256 KB**, then compose separate cards (not one multifunction JAR):

```bash
./gradlew :cards:mem-expansion:jar :cards:uart-8250:jar :cards:rtc-mm58167:jar \
  :cards:lpt:jar :cards:gameport:jar

./gradlew :k8086-emulator:run --args="disks/fd.img \
  --card cards/mem-expansion/build/libs/mem-expansion-1.0-SNAPSHOT.jar \
  --card cards/uart-8250/build/libs/uart-8250-1.0-SNAPSHOT.jar \
  --card cards/rtc-mm58167/build/libs/rtc-mm58167-1.0-SNAPSHOT.jar \
  --card cards/lpt/build/libs/lpt-1.0-SNAPSHOT.jar \
  --card cards/gameport/build/libs/gameport-1.0-SNAPSHOT.jar"
```

Set motherboard `baseMemoryKb=256` in the wizard (or CLI motherboard options). `mem-expansion`
defaults assume conventional starts at `0x40000` (256K) and fills to 640K, plus 128K UMB at
`D0000`. POST probes COM1/COM2 and LPT1/LPT2 into the BDA; INT 12h reports 640K.

Cards map I/O, memory, IRQs, and DMA only through `IsaHost` during `attach`.
Network cards also call `IsaHost.attachNic(networkId, mac)` to join a host virtual network.
