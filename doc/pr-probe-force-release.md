# Add force/release support for writable probes using SSA references

## Summary

- Add `force`, `release`, `forceInitial`, and `releaseInitial` to `RWProbe`.
- Add an opt-in `forceable = true` argument to `Wire`, `Reg`, `RegInit`, and
  `Node`; existing declarations remain non-forceable by default.
- Bind writable probes to the declaration's second SSA result instead of
  constructing `firrtl.ref.rwprobe` operations or allocating internal symbols.
- Keep `ref.send` for read-only probes and reject writable bindings to literals
  or declarations that were not created as forceable.
- Correct the operation name emitted by `RefReleaseInitialApi` to
  `firrtl.ref.release_initial`.

## Usage

Inside a generator with a writable probe field and the corresponding layer:

```scala
val target = Wire(UInt(8), forceable = true)
target := io.data
layer("Test"):
  probe.x <== target
  probe.x.force(io.data, io.clock, io.forceEnable)
  probe.x.release(io.clock, io.releaseEnable)
```

The clocked methods take an explicit clock and enable and execute on rising
edges. The initialization variants take `(value, enable)` and `(enable)`,
respectively. These methods are not available on read-only probes.

Writable binding currently supports whole Wire/Reg/Node declarations
(including RegInit), not ports or aggregate subfields. Ordinary signal users
continue to use the declaration's first SSA result.

## Validation

- ProbeForceSpec: 4/4 passed, including SSA reference reuse, type restrictions,
  rejection of non-forceable targets, and reset-register support.
- ReferableSpec: 8/8 passed.
- LayerSpec: 1/1 passed.
- Zaozi lit suite: 9/10 passed. The new ProbeForce.scala case passes MLIR
  checks but fails during firtool's combinational-loop analysis.
- `git diff --check` passed.

## Known Blocker

The pinned CIRCT revision
`306d4f5c0f1b03575a17665d8f2592c33feb8be0` crashes in
`firrtl-check-comb-loops` for a forceable register reference routed through
a layer-colored `ref.cast`. This is an internal equivalence-set assertion,
not a diagnostic reporting an actual combinational loop.

See the [bug record](bugs/circt-forceable-ref-cast-comb-loops.md) and its
[standalone reproducer](bugs/circt-forceable-ref-cast-comb-loops.mlir).
The failing regression remains enabled; no compiler checks are disabled.
End-to-end Verilog validation is still blocked on resolving this CIRCT issue.
