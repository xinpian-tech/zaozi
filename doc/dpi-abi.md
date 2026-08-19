# DPI ABI

**Version: 1.0**

This is the stable interface between a generated lib model and an external driver
(the "frontend"). A UT module's `(IO, Probe)` yields a DPI **contract** (`dpi.json`);
from that contract the framework lowers a lib model (`generated.sv`) and generates a
DPI-export wrapper (`<dut>_dpi.sv` + `<dut>_dpi_capi.cpp`). Built together with
`verilator --lib-create` they become a shared library the frontend loads and drives.
The frontend owns the loop; the framework only produces artifacts.

The ABI is versioned. `dpi.json` carries `abiVersion`; a consumer must reject a
contract whose `abiVersion` it does not understand.

## The contract — `dpi.json`

```json
{
  "dut": "AbsValUT_width8",
  "ports": [
    { "name": "A",      "role": "Drive", "width": 8, "signed": false },
    { "name": "A",      "role": "Probe", "width": 8, "signed": false },
    { "name": "ABSVAL", "role": "Probe", "width": 8, "signed": false }
  ],
  "abiVersion": "1.0"
}
```

- **`name`** — the stable logical name (a Drive and a Probe may share it).
- **`role`** — `Drive` / `Clock` / `Reset` (module inputs), or `Probe` (observed points).
- **`width`** — bit width. This ABI version defines widths **1..64** only (see Extension points).
- **`signed`** — how the frontend should interpret the value; it does not change the wire.

Drive/Clock/Reset are derived from the DUT IO's flipped (input) fields; Probe points
from its Probe. Aligned outputs are intentionally not on the contract — a DUT exposes
what it wants observed through its Probe.

## Type mapping (IEEE 1800-2017 §35, Annex H)

Each port maps to a DPI-C scalar, and to the matching C type:

| width | SV DPI type | svdpi type   | C type      | ctypes        |
|-------|-------------|--------------|-------------|---------------|
| 1..8  | `byte`      | `svByte`     | `char`      | `c_byte/c_ubyte`  |
| 9..16 | `shortint`  | `svShortInt` | `short`     | `c_short/c_ushort`|
| 17..32| `int`       | `svInt`      | `int`       | `c_int/c_uint`    |
| 33..64| `longint`   | `svLongInt`  | `long long` | `c_longlong/c_ulonglong` |

Signedness is a display/interpretation choice: `poke` accepts a signed value (SV
truncates to the port width); `peek` returns the raw bits, and the frontend reinterprets
per the port's `signed` flag.

## C symbols

The shared library exports these plain-C symbols. Every data symbol is **handle-first**:
it takes the model handle and binds the DPI scope from it.

| symbol | signature | meaning |
|--------|-----------|---------|
| `sim_new`    | `void* sim_new()`            | construct a model; returns an opaque handle |
| `sim_eval`   | `void sim_eval(void* h)`     | advance the model one settle (time is the model's, not DPI's) |
| `sim_delete` | `void sim_delete(void* h)`   | destroy the model |
| `dpi_poke_drive_<name>` | `void dpi_poke_drive_<name>(void* h, <ctype> v)` | drive an input port |
| `dpi_poke_<clock>` / `dpi_poke_<reset>` | `void dpi_poke_<name>(void* h, <ctype> v)` | drive clock / reset |
| `dpi_peek_probe_<name>` | `<ctype> dpi_peek_probe_<name>(void* h)` | read a probe point |

Naming: drive ports are `drive_<name>`, probes `probe_<name>`, clock/reset keep their
contract name. The `dpi_poke_`/`dpi_peek_` C wrappers set the scope from the handle and
call the scope-bound `export "DPI-C"` functions (`poke_<...>` / `peek_probe_<...>`),
which are an implementation detail, not the ABI.

## Driving loop

One cycle:

1. For every `Drive` port, `dpi_poke_drive_<name>(h, value)` from the stimulus.
2. Step:
   - **combinational** DUT (no `Clock` port): a single `sim_eval(h)`.
   - **sequential** DUT: `dpi_poke_<clock>(h, 0); sim_eval(h); dpi_poke_<clock>(h, 1); sim_eval(h);`
3. For every `Probe` port, read `dpi_peek_probe_<name>(h)`.

The stimulus source (a solver, SVA-assertion sampling, a fixed vector) is out of scope
for this ABI; `ut_frontend.py` reads it as `{ "<drive-port>": [v0, v1, ...] }`.

## Extension points (reserved, not yet implemented)

- **Ports wider than 64 bits** — map to a packed vector `bit [W-1:0]` → `svBitVecVal*`
  (canonical little-endian `uint32` chunks, `ceil(W/32)` of them). Passed by pointer; no
  allocation. A minor ABI version will define the C signatures.
- **Arrays / dynamic length** (batching a whole waveform, a variable-width bus) — map to an
  open array `svOpenArrayHandle`. The simulator owns the storage; the C side never
  allocates it, and reads it through `svSize` / `svLow` / `svHigh` / `svGetArrElemPtr*`
  (packed-element layout is canonical, so no `memcpy`). Reserved for a future minor version.

## Backend / portability notes

- **Scope binding** is backend-specific. On Verilator the scope is `TOP.<dut>_dpi`, and one
  scope is shared per top name — so the handle-first ABI is *shaped* for multiple instances,
  but Verilator is single-instance in practice. A backend with per-instance scopes can honour
  the handle fully without an ABI change.
- **Stepping** (`sim_eval`) is the model's evaluation, not DPI. A non-Verilator backend
  provides its own `sim_new`/`sim_eval`/`sim_delete` implementation; the `dpi_poke`/`dpi_peek`
  layer and the contract are unchanged.
- **glibc**: the frontend process must share the C runtime the model was built against (run
  `python3` in the same dev shell as `verilator`).
