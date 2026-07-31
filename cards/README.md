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
| `ram-umb` | `com.trugath.k8086.cards.ram-umb` | Upper memory block RAM |
| `adlib` | `com.trugath.k8086.cards.adlib` | AdLib OPL ports + square-wave audio (not full FM) |
| `heartbeat` | `com.trugath.k8086.cards.heartbeat` | Periodic IRQ heartbeat |
| `ems-window` | `com.trugath.k8086.cards.ems-window` | Page-frame EMS window |
| `de220` | `com.trugath.k8086.cards.de220` | D-Link DE-220 NE2000 NIC (virtual NAT network) |

Cards map I/O, memory, IRQs, and DMA only through `IsaHost` during `attach`.
Network cards also call `IsaHost.attachNic(networkId, mac)` to join a host virtual network.
