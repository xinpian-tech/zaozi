# The UT testbench (Model B) and its ABI

The framework derives an **ABI contract** (`abi.json`) from a UT module's `(IO, Probe)`, lowers a
lib model, and — for the testbench — a **sim-dialect harness** that the simulator runs. The SV
owns the loop; a C callback (generated from `abi.json`) supplies stimulus and observes probes.

## Artifacts

- **`abi.json`** — the contract: `{ dut, ports: [{name, role, width, signed}], abiVersion }`.
  Roles are `Drive` / `Clock` / `Reset` (module inputs) and `Probe` (observed points), all derived
  from the DUT's IO (flipped inputs) and Probe.
- **the harness** (`generated.sv` + layer/ref split files) — from `emitTestbench`. Elaborated via
  the sim dialect: each cycle it reads the DUT's probes and calls `import "DPI-C" <dut>_tick(...)`,
  driving the DUT with the value the callback returns. `firtool` lowers the FIRRTL DPI intrinsic to
  the `import "DPI-C"` declaration and a clocked call — no hand-written harness SV.
- **`ut_top.sv`** ([[Driver]]) — the *only* hand-written SystemVerilog: a clock oscillator
  (`always #5`), a reset sequence, and the harness instance. It is irreducible because CIRCT has no
  MLIR construct for a `#`-delay (see the backlog).
- **`<dut>_tick.c`** — the callback, generated from `abi.json`: `void <dut>_tick(<probes by value>,
  <drive>*)`. It observes the probes and supplies the per-cycle drive from `stimulus.txt` (one value
  per line). Single drive port for now (the FIRRTL DPI call yields one result).

Build them with `verilator --binary --top-module ut_top … generated.sv ut_top.sv layers-*.sv
ref_*.sv <dut>_tick.c`, run in a directory containing `stimulus.txt`. The observed probe each cycle
is the DUT's response to the *previous* drive (the DPI call is clocked).

## Why this shape (Model B)

Two ways to expose a DUT to an external driver:

- **Model A** — C owns the loop: `export "DPI-C"` poke/peek + step the model from C. Needs DPI
  *export*, which CIRCT's ExportVerilog cannot emit (it emits `import "DPI-C"` only), and stepping
  is the Verilator model API (not portable).
- **Model B** — SV owns the loop: self-clock + `import "DPI-C"` callback each cycle. Its DPI is the
  *import* direction, which the sim dialect **can** generate (`sim.func.dpi` + `sim.func.dpi.call`),
  and it is pure SV + standard svdpi, so it runs on any simulator (Verilator/VCS/FireSim).

Model B is what this framework uses: the harness is dialect-native, and the callback is the C ABI
generated from `abi.json`.

## Type mapping (IEEE 1800-2017 §35, Annex H)

Each contract port maps to a DPI-C scalar / packed vector:

| width  | SV DPI type   | C type          |
|--------|---------------|-----------------|
| 1..8   | `byte`        | `char`          |
| 9..16  | `shortint`    | `short`         |
| 17..32 | `int`         | `int`           |
| 33..64 | `longint`     | `long long`     |
| >64    | `bit [W-1:0]` | `svBitVecVal*` (packed `uint32` chunks) |

## CIRCT gaps / backlog

- **Clock `#`-delay** — the sim dialect has no delay/wait op, so the clock oscillator cannot be
  dialect-generated; it is the one hand-written primitive (`ut_top.sv`). CIRCT models time in the
  **LLHD** dialect, which is not on the firtool SV-emit path. Adding a delay/time construct there
  would let the oscillator be generated too.
- **DPI export** — CIRCT can read `export "DPI-C"` (ImportVerilog) but not emit it. Not needed for
  Model B; only Model A would need it.
- **Multi-drive** — the FIRRTL DPI call yields one result, so the tick has one drive output; a
  multi-drive DUT needs the multi-output shape.
