# Source-local LTL argument diagnostics

The retry4 ETHMAC answer contained two `BigInt(0xFFFFFFFF)` expressions. Scala
evaluates the literal as Int(-1) before BigInt construction. Rejecting -1 for an
unsigned input was correct; classifying the resulting exception as an opaque
infrastructure/lower failure was not.

The unchanged saved answer was compiled and elaborated with the updated framework
in `/var/storage/workspaces/clo91eaf/ltl-argument-diagnostic-20260915-v1`.
It now returns exit 2, phase `elaboration-check`, kind `model_argument_error`,
code `ltl_unsigned_range`, at `model.ltl:22`, with `model_repair_allowed=true`.
The diagnostic points to `isOnes` or text-based BigInt construction. No model
was called, no historical answer was rewritten, and this is not a new benchmark
or evidence of improved model coverage. Elaboration stops before JG.

Implementation:

- `LtlArgumentException` preserves API error code, caller file and line.
- The fixed runner exports structured diagnostics rather than parsing stack traces.
- The harness requires a recognized range code, exact generated source file,
  and a location inside the model body before permitting repair. Unknown width
  and all other lower/toolchain failures still stop closed.
- Repairs use the existing attempt budget and private journal, not silent edits.
- The skill explains Scala Int/Long overflow and calls to existing helpers using
  a generic constant; no DUT addresses, operands or historical answers are added.
- Unsigned and signed range checks remain strict: no masking or truncation.

Validation includes real Scala helper lowering at widths 1, 13, 32 and 65,
numeric-text constants, malformed/foreign diagnostics, and the existing repair
loop with fake provider responses. FrameworkLtlTest and FrameworkExamplesTest:
7 passed. Full Python regression: 505 tests, 466 passed, 39 skipped.
Live inference and another design campaign were not started for this change.
