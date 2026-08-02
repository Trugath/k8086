# System ROMs (U18 / U19)

k8086 ships **rmDOS** clean-room XT system ROMs as the default BIOS
(MIT; see root [LICENSE](../LICENSE) and [NOTICE](../NOTICE)):

| File | Socket | Size | Linear address |
|------|--------|------|----------------|
| `u18.bin` | U18 | 32768 | `0xF8000–0xFFFFF` |
| `u19.bin` | U19 | 8192 | `0xF6000–0xF7FFF` |
| `fdrom.bin` | Fixed Disk option ROM | 2048 | `0xC8000` (when HD enabled) |

Together U18+U19 occupy `0xF6000–0xFFFFF` (40 KB). The 8088 reset vector at `0xFFFF0`
is a far jump `JMP F000:E05B`. Cassette BASIC is intentionally omitted.
`fdrom.bin` is the guest Fixed Disk INT 13h option ROM; workstation VM snapshots
copy it beside U18/U19 so HD-enabled guests can run `PARTEDIT` / `FORMAT C:`.

Prebuilt images are committed so a standalone clone runs without the parent
[rmDOS](https://github.com/Trugath/rmdos) tree. When developing both projects,
rebuild from rmDOS (`make bios` / `make install-roms`) and copy here.

## Changing the ROMs

**CLI / env (single-instance emulator):**

```bash
export K8086_U18_ROM=/path/to/u18.bin
export K8086_U19_ROM=/path/to/u19.bin
export K8086_FDROM=/path/to/fdrom.bin
./gradlew :k8086-emulator:run --args='disks/fd.img'
```

**Workstation (multi-VM):**

- **New…** — the create wizard includes a **ROMs** step for U18, U19, and the
  Fixed Disk option ROM (defaults `roms/u18.bin` / `roms/u19.bin` /
  `roms/fdrom.bin`). Browse to override; images are snapshotted under
  `vms/<id>/roms/`.
- **Edit…** — available when the VM is **stopped**. Opens the full settings
  dialog (name, U18/U19/fdrom, motherboard, adapters, drives, networks, cards);
  the host re-copies ROM images into the VM’s snapshots when paths change.

Per-VM snapshots live under `~/.k8086/vms/<id>/roms/{u18,u19,fdrom}.bin` and are
stored as absolute paths in `vm.properties`. Those files are treated as immutable
for the life of that definition until you Edit and replace them.

See also [docs/architecture.md](../docs/architecture.md) for how
`Machine` / `loadSystemRoms` maps the chips.
