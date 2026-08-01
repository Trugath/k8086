# Disk images

| File | Description |
|------|-------------|
| `fd.img` | rmDOS boot floppy (720 KB) used by integration tests and manual boots |

MIT-licensed rmDOS image; see root [LICENSE](../LICENSE) and [NOTICE](../NOTICE).
Committed for a standalone clone. When developing alongside
[rmDOS](https://github.com/Trugath/rmdos), refresh with `make install-floppy` /
`make os` from that tree.

## Usage

```bash
./gradlew :k8086-emulator:run --args='disks/fd.img'
```

Optional hard disk (created blank at ~10 MB XT geometry if the path does not exist yet):

```bash
./gradlew :k8086-emulator:run --args='disks/fd.img hd.img'
# Boot from hard disk:
./gradlew :k8086-emulator:run --args='disks/fd.img @hd.img'
```

In the multi-VM workstation (`./gradlew :k8086-app:run`), attach the same images through the
New-VM wizard; definitions live under `~/.k8086/vms/`.

## Hard disk model

- Default geometry: **306 cylinders × 4 heads × 17 SPT** (~10 MB, ST-412 style).
- Enabling the HD controller maps an XT Fixed Disk Adapter (Xebec-style) at **`0x320` / IRQ5 / DMA3**.
- Guest INT 13h for `DL ≥ 0x80` is owned by the guest **C800 Fixed Disk option ROM** by default (`roms/fdrom.bin`). Opt into the host Fixed Disk BIOS with `--hd-int13-bios` / `K8086_HD_INT13_BIOS=1`. A legacy direct-image INT 13h shim remains available via `useInt13Shim`.
- Prefix the HD path with `@` to boot from it (`DL=0x80` at reset).

See [docs/architecture.md](../docs/architecture.md) for the full boot path.

## Format

`fd.img` is a raw **720 KB** FAT12 floppy image (80 cylinders × 2 heads × 9 sectors × 512 bytes).
The emulated uPD765 FDC reads it during BIOS INT 13h floppy I/O.

Runtime-created hard-disk images such as `hd.img` are not tracked; see the root `.gitignore`.
