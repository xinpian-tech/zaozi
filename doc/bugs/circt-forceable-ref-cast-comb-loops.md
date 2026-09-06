# FIRRTL combinational-loop analysis crashes on a cast forceable register reference

## Status

- Recorded: 2026-09-08.
- Reproduced with CIRCT `306d4f5c0f1b03575a17665d8f2592c33feb8be0`,
  pinned by this repository's `flake.lock` (firtool 1.154.0).
- Reproduced on Linux x86-64 with assertions enabled in the affected code.
- No upstream issue or fix has been submitted as part of this change.
- Blocks `ZAOZI :: ProbeForce.scala` during Verilog generation.

## Reproduction

Run from the repository root, using the pinned development environment:

```sh
nix develop --no-update-lock-file
circt-opt doc/bugs/circt-forceable-ref-cast-comb-loops.mlir -o /dev/null
circt-opt --firrtl-check-comb-loops doc/bugs/circt-forceable-ref-cast-comb-loops.mlir -o /dev/null
```

The first command after entering the shell succeeds: the standalone
[MLIR file](circt-forceable-ref-cast-comb-loops.mlir) passes parsing and
verification. The second aborts (observed process exit code 134).

The reproducer contains a register, an output driven by that register, and a
writable probe. The reference is routed as follows:

```text
forceable register's SSA reference
  -> ref.cast adding layer @Test
  -> ref.define to an output probe
```

No force/release statement is needed to trigger the crash. There is no
combinational feedback in this design.

## Expected and Actual Results

Expected: combinational-loop analysis succeeds without reporting a cycle.

Actual:

```text
llvm/ADT/EquivalenceClasses.h:192:
Assertion `MI != member_end() && "Value is not in the set!"' failed.
```

Relevant frames:

```text
DiscoverLoops::addToPortPathsIfRWProbe
DiscoverLoops::dfsTraverse
CheckCombLoopsPass::runOnOperation
```

This is a failed internal bookkeeping assumption, not a detected-cycle
diagnostic. As a control, an uncolored probe defined directly from the same
register reference, without the cast/layer block, passed the analysis.

## Source Analysis

The following analysis is based on the pinned revision, not a claim about
current upstream HEAD:

1. In [CheckCombLoops.cpp](https://github.com/llvm/circt/blob/306d4f5c0f1b03575a17665d8f2592c33feb8be0/lib/Dialect/FIRRTL/Transforms/CheckCombLoops.cpp),
   `constructConnectivityGraph` matches `hw::CombDataFlow` before
   `Forceable` in a `TypeSwitch`.
2. `RegOp` implements both interfaces. Its
   [computeDataFlow implementation](https://github.com/llvm/circt/blob/306d4f5c0f1b03575a17665d8f2592c33feb8be0/lib/Dialect/FIRRTL/FIRRTLOps.cpp)
   returns no combinational edges. The `Forceable` branch, which would call
   `recordProbe(data, ref)`, is therefore skipped for the register.
3. `RefDefineOp` unions its source and destination in `rwProbeClasses`.
   With the cast present, these are the cast result and the output probe,
   not the register's original reference.
4. `RefCastOp` has no dedicated handling that unions its input and output
   as aliases of the same writable target. Generic dataflow handling does
   not establish this equivalence.
5. `addToPortPathsIfRWProbe` later calls
   `rwProbeClasses.getLeaderValue(getOrAddNode(defOp.getDataRef()))`.
   The original register reference was never inserted into that equivalence
   set, so the lookup asserts.

The direct, uncolored control avoids the assertion because `ref.define`
itself inserts the original register reference into the set. Passing that
control does not prove that all forceable-register dataflow is modeled correctly.

## Suggested Upstream Fix

These are proposed changes, not an implemented or verified fix:

- Register forceable-target metadata independently of the mutually exclusive
  combinational-dataflow dispatch, while retaining the register's sequential
  boundary.
- Track alias equivalence across casts between writable reference types.
- Add regressions for forceable registers and reset registers, layer-colored
  casts, shared references, and cross-module force paths. Include real
  combinational-loop cases to verify that detection remains effective.

Simply ignoring a missing equivalence-set entry or disabling the pass would
hide incomplete analysis and is not a sufficient fix.

## Zaozi Impact

The SSA implementation and its 13 focused unit tests pass, but the complete
Zaozi lit run is 9/10: `ProbeForce.scala` passes its MLIR checks and crashes
in firtool at this pass. The test remains enabled and is not marked XFAIL.
No fallback to symbol-based references or check-disabling workaround has
been added.
