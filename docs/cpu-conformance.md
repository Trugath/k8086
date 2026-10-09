# CPU conformance notes

The 80386 corpus was captured on an Intel386 EX. The emulator's `80386`
model targets the architectural CPU in a PC, rather than the EX's integrated
peripherals. Hardware captures therefore require interpretation against the
instruction specification and the capture machine's configuration.

## Shift and rotate flags

The 386 masks shift counts to five bits. A zero count preserves flags. OF is
defined for a count of one and undefined for larger counts. AF is undefined
for nonzero shifts; rotates preserve the other status flags. SHL/SHR CF is
undefined when the count reaches the operand width. The hardware runner
calculates masks from the actual instruction and count, including immediate
counts and the `/6` SHL alias, instead of relying solely on conservative CSV
masks. This checks defined OF and CF values that the CSV sometimes excludes.

References: Intel's [386 shift instruction specification](https://www.ardent-tool.com/CPU/docs/Intel/386/manuals/prref386/SAL.htm),
[rotate specification](https://www.ardent-tool.com/CPU/docs/Intel/386/manuals/prref386/RCL.htm),
and [SDM volume 2B, SHL/SHR flags](https://cdrdv2-public.intel.com/782151/253667-sdm-vol-2b.pdf).

MOO exception FLAGS addresses are physical addresses. The high byte of a
valid saved FLAGS word follows the low byte across a physical 64 KiB boundary;
the runners must not wrap a physical address as though it were a segment offset.

## Architectural and hardware reports

Run architectural conformance with:

```sh
./gradlew :k8086-emulator:singleStep80386Test -PsingleStepLimit=10000
```

The bulk corpus test checks all unclassified captures. Each of the fifteen
classified captures has an individually named JUnit test. The nine IDIV cases
use an architectural oracle that requires divide error, preserved general and
data-segment registers, the correct ESP and restart CS:IP, and saved control
flags. Six peripheral captures appear as explicit unresolved-state skips.
This is a passing architectural suite with six deferred peripheral cases,
not a claim that every EX hardware capture matches.

Known captures are identified by filename, index, and SHA-1. A changed capture
fails classification validation. These fixtures run in full even with a small
sampling limit; `singleStepOpcodes` still filters them. The normal test suite
also checks the nine overflow operand pairs independently of the corpus, plus
the signed byte quotient boundaries -129, -128, 127, and 128.

To compare against raw EX hardware results, including the fifteen differences:

```sh
./gradlew :k8086-emulator:singleStep386ExHardwareTest -PsingleStepLimit=10000
```

The hardware task deliberately fails until the hardware differences are
resolved. Architectural and hardware HTML reports are separate, under
`k8086-emulator/build/reports/tests/singleStep80386Test/` and
`k8086-emulator/build/reports/tests/singleStep386ExHardwareTest/`.

## EX capture differences

The exhaustive configured 386 corpus currently has nine byte IDIV captures
that expect success with a quotient outside the signed byte range. The core
raises divide error. Intel's [386 EX specification update](https://kib.kiev.ua/x86docs/Intel/486/272873-009.pdf),
specification change 7, documents missed overflow exceptions and incorrect
quotients for a limited subset of IDIV calculations. It does not describe an
algorithm sufficient to reproduce the defect. The architectural tests require
divide error; the hardware profile retains the original successful-division
expectations. The CPU overflow check is unchanged.

Six additional IN captures overlap ports 22h/23h. The
[Intel386 EX user's manual](https://manualzilla.com/doc/5749888/intel386%E2%84%A2-ex-embedded-microprocessor-user-s-manual),
section 4.5, assigns these ports to the on-chip REMAPCFG register. The captures
do not supply its initial state, and their external bus data does not explain
the expected register values. The runner does not synthesize peripheral state
from expected final CPU registers. These cases remain deferred in the
architectural profile and failures in the hardware profile, pending sufficient
capture configuration or a separately specified EX peripheral model.

The corpus README's "Test Methodology" section explains that successive tests
normally reuse the chip through SMM without a reset. The historical generator
[register-load implementation](https://github.com/dbalsom/arduinoX86/blob/0ecce498144db5ee31119ff52abaf4e619042792/platformio/ArduinoX86/src/CommandServer.cpp#L798)
likewise explicitly avoids resetting on an SMM load. Its
[setup program](https://github.com/dbalsom/arduinoX86/blob/0ecce498144db5ee31119ff52abaf4e619042792/platformio/ArduinoX86/asm/cpu_setup_386ex.asm)
enables expanded I/O after a reset. Thus a reset value alone cannot establish
the REMAPCFG state for a standalone capture later in the run. Reproducing these
I/O results requires the actual initial peripheral state or the prior state
transitions; the CPU register/RAM snapshots and external bus data are insufficient.
