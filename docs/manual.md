# k8086 user manual

Reference for the multi-VM **workstation** (`run.bat` / `./run.sh` from a release
zip, or `./gradlew :k8086-app:run` from source). For a
first boot walkthrough, see [Getting started](getting-started.md).

Persistence:

| Path | Contents |
|------|----------|
| `~/.k8086/vms/<id>/` | VM definition (`vm.properties`) and ROM snapshots |
| `~/.k8086/networks/` | Virtual NAT network definitions |

Disk image paths you choose (floppies / hard disks) stay where you put them; the
host claims them exclusively while a VM is running.

---

## Manager window

![Manager](screenshots/01-manager.png)

Toolbar:

| Button | Action |
|--------|--------|
| **New…** | Create VM wizard (includes ROMs) → name |
| **Edit…** | Full VM settings dialog (stopped / error only) |
| **Networks…** | Manage virtual NAT networks |
| **Start** / **Stop** | Run or cooperatively stop the selected VM |
| **Console** | Open the CGA console (VM must be starting, running, or paused) |
| **Debug** | Open the CPU/memory debugger |
| **Delete** | Remove the VM definition (disk images on disk are kept) |
| **Refresh** | Reload the VM list |

The left list shows each VM and its state. The details pane shows id, live
metrics (instruction count, uptime, floppies), motherboard/adapters, and ROM
paths.

![Manager with a paused VM](screenshots/08-manager-running.png)

---

## New VM wizard

Opened from **New…**. Nothing is applied until you click **Create** on Review
(then name the VM). System ROM paths are chosen on the **ROMs** step.

### Welcome

![Welcome](screenshots/02-wizard-welcome.png)

### ROMs

U18 (32 KB), U19 (8 KB), and Fixed Disk option ROM (`fdrom`, 2 KB at C800:).
Defaults are `roms/u18.bin`, `roms/u19.bin`, and `roms/fdrom.bin`. On create,
copies under `~/.k8086/vms/<id>/roms/` are immutable snapshots for that VM.

![ROMs](screenshots/02-wizard-roms.png)

### System

CPU model (8088 / 8086 / real-mode 80286), conventional memory (64–640 KB),
optional software 8087, SW1 initial video mode, and optional continuous POST loop.

![System](screenshots/02-wizard-system.png)

### Adapters

CGA (or none), COM1 UART, floppy controller, and optional XT hard-disk controller.

![Adapters](screenshots/02-wizard-adapters.png)

### Drives

Floppy image paths (A:/B:/…) when the FDC is enabled. With the HD controller
enabled, set image path, XT I/O / IRQ / DMA, size for new images, and optional
boot-from-HD.

![Drives](screenshots/02-wizard-drives.png)

### Network

Lists virtual networks available to NIC cards (see [Networks](#networks)). Create
or edit networks here or from the manager **Networks…** dialog.

![Network](screenshots/02-wizard-network.png)

### Expansion

ISA plugin JARs discovered on the classpath / catalog (AdLib, DE-220, EMS window,
UMB RAM, samples, …). Enable cards and use **Configure…** for per-card options.
**Add JAR…** loads an external plugin.

![Expansion](screenshots/02-wizard-cards.png)

### Review

Summary plus validation. **Create** returns the setup to the manager (CLI uses
**Start** and boots immediately).

![Review](screenshots/02-wizard-review.png)

### Edit settings (after create)

**Edit…** on a stopped VM opens a **settings** dialog with the same categories as
the create wizard (General, ROMs, System, Adapters, Drives, Network, Expansion,
Review). Click a category in the sidebar; **Save** applies changes and refreshes
ROM snapshots when paths change.

![VM settings](screenshots/03-vm-settings.png)

---

## Networks

![Virtual networks](screenshots/04-networks.png)

Each network has an id, display name, gateway IP, subnet mask, and optional DHCP
range. Guests with a NIC card (for example DE-220) attach by network id.

![New / edit network](screenshots/05-network-edit.png)

The default network is typically `10.0.2.2/24` with DHCP `10.0.2.15`–`10.0.2.31`
(QEMU-like NAT layout). See [architecture.md](architecture.md) for the host/NAT
module map.

---

## Console

![Console](screenshots/06-console.png)

Opens only when the VM is starting, running, or paused. Focus the black display
to send keyboard scan codes to the guest. Tab / Shift+Tab stay on the display
(they are not used to move focus onto the toolbar). **Click** the display to grab
the mouse (relative motion + buttons → COM1 Microsoft serial mouse at ~1200 7N1);
**Esc** releases the grab. Right-click pastes clipboard text as guest keystrokes
when the mouse is not grabbed. Run `BIN\MOUSE` once so guest apps see INT 33h
(`MOUSE /U` unloads). COM1 is the wired port; do not expect a PS/2 or bus mouse.

| Control | Role |
|---------|------|
| **Ctrl+Alt+Del** | Inject CAD (warm boot path) |
| **Change *n*:** | Insert image, eject, or cancel for that floppy unit |
| Pause / play | Cooperative pause / resume |
| Turbo (⏩) | Toggle max-speed vs realtime pacing |
| Speaker | Mute / unmute PC speaker (and card audio when applicable) |
| **Debug** | Open the debugger for this VM |

Closing the console does not stop the VM.

---

## Debugger

![Debug window](screenshots/07-debug.png)

Also available from the manager **Debug** button.

| Control | Role |
|---------|------|
| **Pause** / **Resume** | Cooperative run-state |
| **Step** | One machine iteration while paused (auto-pauses if needed) |
| **Refresh** | Reload registers / memory / breakpoints |

- **CPU** — general registers, flags (uppercase = set), CS:IP, linear address,
  next instruction bytes; `(halted)` when the CPU is in HLT.
- **Memory** — hex dump from a linear address; **CS:IP** / **SS:SP** jump helpers.
- **Breakpoints** — linear CS:IP addresses; free-run pauses before a hit; **Step**
  ignores the hit so that instruction can proceed.

Mnemonic disassembly is not implemented yet (lengths and hex only).

---

## Single-instance emulator (optional)

Without the multi-VM host:

```bash
./gradlew :k8086-emulator:run --args='disks/fd.img'
./gradlew :k8086-emulator:run   # no args → same wizard; finish button is Start
```

Useful flags: `--headless`, `--serial-log PATH`, `--quiet`, `--card path/to.jar`.
See the [README](../README.md) for hard-disk CLI forms and card examples.

---

## Regenerating screenshots

From the repo root (requires a display). If `~/.k8086/vms` is empty, the task
creates a temporary `doc-screenshot` VM with `disks/fd.img` for console/debug
shots:

```bash
./gradlew :k8086-app:docScreenshots
```

Output directory: `docs/screenshots/` (override with `-PdocScreenshotDir=…`).
