k8086 Workstation
=================

IBM 5155 / 5160-class PC emulator (multi-VM manager + CGA console).

Requirements
------------
- JDK 21 or newer (java on PATH, or JAVA_HOME set)
- A display for the workstation UI (Swing manager / console windows)

Run (workstation UI)
--------------------
Windows:  double-click run.bat  (or:  run.bat)
Linux / macOS:  ./run.sh

Run (CLI / headless)
--------------------
Windows:  run-cli.bat disks\fd.img --headless --quiet --cga-expect A:>
Linux / macOS:  ./run-cli.sh disks/fd.img --headless --quiet --cga-expect 'A:>'

Included assets
---------------
roms/u18.bin, roms/u19.bin, roms/fdrom.bin  — default rmDOS system ROMs
disks/fd.img                                — rmDOS boot floppy

VM definitions are stored under ~/.k8086/vms/

License
-------
MIT — see LICENSE and NOTICE in this folder.
GitHub: https://github.com/Trugath/k8086
