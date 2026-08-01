k8086 Workstation
=================

IBM 5155 / 5160-class PC emulator (multi-VM manager + CGA console).

Requirements
------------
- JDK 21 or newer (java on PATH, or JAVA_HOME set)
- A display (Swing manager / console windows)

Run
---
Windows:  double-click run.bat  (or:  run.bat)
Linux / macOS:  chmod +x run.sh && ./run.sh

Included assets
---------------
roms/u18.bin, roms/u19.bin  — default rmDOS system ROMs
disks/fd.img                — rmDOS boot floppy (use in the New VM wizard)

VM definitions are stored under ~/.k8086/vms/

License
-------
MIT — see LICENSE and NOTICE in this folder.
GitHub: https://github.com/Trugath/k8086
