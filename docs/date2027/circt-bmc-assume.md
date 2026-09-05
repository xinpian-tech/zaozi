# `verif.assume` is not enforced across an instance boundary

Found while building the goal-directed arm (2026-08-30). It matters beyond that arm: an assumption
that is silently dropped does not make a query fail, it makes it answer **the wrong question**, and
every witness this project generates is produced by exactly such a query.

## What happens

`circt-bmc` does not enforce a `verif.assume` on a top-module port when that port also feeds an
`hw.instance`. The instance behaves as though it reads an unconstrained copy of the port.

`circt-bmc-assume-repro.mlir` is the whole reproducer: `Top3` assumes its input `x` is never high and
asserts that a child module's registered copy of `x` is never high. The child's register has a pinned
initial value of 0, so with the assumption honoured the assertion cannot fail.

```
circt-bmc circt-bmc-assume-repro.mlir -b 4 --module Top3 --rising-clocks-only --shared-libs=$Z3

counterexample for Top3:
cycle 0:
  inst/r_next = 0x1     <- x was 1, though x is assumed never high
  inst/r_state = 0x0
cycle 1:
  inst/r_next = 0x0
  inst/r_state = 0x1
Assertion can be violated!
```

`--flatten-modules` does not change the verdict. Observed on the pin in `flake.nix`
(`utlib-circtpin`), CIRCT built from `circt-install`, with `--rising-clocks-only`.

## Why it took a while to see

The obvious sanity check passes. On the same file, replacing the assertion with the *assumed*
expression reports "Bound reached with no violations" — which reads as "assumptions work" and is why
this survived so long. It is a vacuous check: it only ever exercises the local copy of the port, the
one that is constrained. The assumption fails specifically on the path into the instance, so only an
assertion that depends on the instance's output can see it.

A module with no instance, a module with a stateless instance, and a module with two assumptions all
honour their assumptions. The bug needs the combination: assumption on a port, assertion downstream
of an instance the port drives.

## What it invalidated here

`Sem`'s intents are asserted, not assumed, so the goals themselves are unaffected — an assertion over
the instance's outputs is the thing being solved for, and those results stand. What breaks is
anything that *narrows* the search:

- **`HavenAluGoalUT`'s `opIs`**, which had been an assumption that the launched opcode is the
  requested one. Under it, a goal of "overflow flag set, on the reserved opcode 15" returned a
  witness — one that launched FP_SUB and merely parked `op` on 15 later. The same goal with the
  opcode tied structurally into the DUT returns `Infeasible`, which is the correct answer. The fix is
  to tie constants into the instance rather than assume them of the port; a tie cannot be ignored.

- **`Txn.assumeResetLow`** has exactly this shape — an assumption on the reset port of a module that
  instantiates the DUT — and is therefore unreliable. This is the already-observed i2c failure where
  72 of 98 witness beats held `arst_i` asserted, which at the time was patched by pinning `arst_i` in
  the stimulus codec. That patch was right, but the diagnosis was not: reset was never being held low
  by the assumption in the first place. Reset is held low in replay because the codec pins it, not
  because the intent assumes it.

The general rule this leaves: **constrain by construction, not by assumption.** A value tied into the
DUT, or a field pinned by the stimulus codec, is enforced by the structure of the model; an
assumption is a request the solver is free to ignore, and here does.

## Still open

Not yet reported upstream, and not yet minimised past the form above (the reproducer still needs the
register — a stateless instance honours the assumption, so the interaction is with state as well as
with the boundary). Worth checking whether a newer CIRCT fixes it before building anything else on
assumptions.
