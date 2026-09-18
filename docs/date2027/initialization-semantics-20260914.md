# Initialization semantics and native sequence repetition (2026-09-14)

Offline backend diagnostics only: no DeepSeek requests, no changes to original
intents, DUT RTL, frozen Stage-1, HAVEN, or historical paired-experiment metrics.

## Two distinct defects

1. **Unknown initial storage:** the UART/SPI FIFO reset initializes pointers and
   counts, not RAM. Original two-state JG witnesses can choose bytes in unwritten
   RAM; native four-state simulation observes X. Resetting the pointers is not
   evidence that storage contents are known. The auxiliary model must represent
   both values and unknown masks, and a target predicate must be known-true.
2. **Output alias mask loss:** Yosys 0.67's split-output encoding emitted an
   alias as `alias_d = canonical_x ? 1'bx : canonical_d; alias_x = 0`.
   This reintroduces an unencoded X into two-state FPV, even though RAM's mask
   was correctly initialized. The old SPI diagnostic contains precisely this
   pattern for repeated atoms 5/7 and 6/8. It is not fixed by more resets.

The adapter already has an explicit solver-only boot to load generated
initializers because this JG version ignores declaration initialization.
That boot initializes encoding metadata and actual defined RTL initial values;
it must not zero unknown DUT RAM. The whole frozen reset sequence follows boot,
with the original clock phase preserved.

## Changes

- Intern repeated Boolean predicates and canonicalize structurally identical
  output ports **before** xprop. Both value and unknown rails share the canonical
  signal. Preserve initialization attributes on removed alias netnames; reject
  conflicting attributes.
- Audit the exported goal/environment rails for combinational reintroduction
  of X/Z and undriven/multiply-driven bits before invoking JG.
- If native replay measures unknown bits in formal-required outputs, switch
  immediately to the known-state auxiliary solver instead of exhausting more
  two-state preferences. Missing diagnostics are not treated as proof of X.
- Keep the original native LTL replay check. No candidate is accepted solely
  because the transformed goal is covered.
- Preserve native consecutive repetition in the auxiliary parser, including
  zero-length, fixed, ranged and unbounded forms. Encode only Boolean leaves;
  do not replace repetition with an approximate delay expansion.

## zaozi API evidence

`p.S.*(4)` is supported, producing the equivalent of `p[*4]`:

- `zaozi/src/Api.scala`: `Sequence.*(n)` and `Sequence.*(min, max)` declarations.
- `zaozi/src/default/SVAApi.scala`: implementation using `ClockedRepeatApi`;
  repeat count may be zero, and an absent upper bound is unbounded.
- `zaozi/tests/src/SVASpec.scala`: fixed, bounded and unbounded repetition tests.

ETHMAC's original JG solve accepted its repeated sequence. The previous failure
was the auxiliary parser rejecting `[*4]`, not an unsupported zaozi expression.
Earlier successful auxiliary goals did not contain this repetition form.

## Verification

- Python regression suite: 439 tests, 400 passed and 39 skipped.
- zaozi `me.jiuyang.zaozitest.SVASpec`: all 52 tests passed.
- Real VCS/JG alias regression: 13 samples; old encoding has 9 unknown-mask
  mismatches, fixed encoding agrees with native values/masks. Covers uninitialized
  RAM, partial reset and aliased predicates. JG reports unwritten-known-true
  unreachable and written-known-true repeated four times covered.
- Existing explicit-boot, conditional-X, valid-history `past` and tristate
  diagnostics all passed. Conditional-X checks 24 samples; tristate checks 80.
- Unmodified saved SPI `rx_fifo_data_toggle_and_full`: encoded cover and
  original native replay passed.
- Unmodified saved ETHMAC `bd_region_lane_writes_and_readback_patterns`:
  encoded cover and original native replay passed.
- UART `rx_two_bytes_00_ff_readback`: no candidate within the original 120-second
  diagnostic solve budget (`undetermined`, not proved unreachable). With a
  separately recorded 600-second diagnostic budget, encoded cover and original
  native replay both passed; encoding plus replay took 509.40 seconds.

The second three-goal diagnostic finished with `status: passed`: SPI 18.03 s,
ETHMAC 52.03 s, UART 509.40 s. Production's default solver budget was not changed.

Artifacts are archived under
`/var/storage/workspaces/clo91eaf/initialization-semantics-20260914/`.
The `rvprobe-init-semantics-20260914-v2` directory contains per-goal alias maps,
rail audits, solver logs and native summaries. These are one-candidate backend
regressions, not a new completed 16-design paired run or four-sequence result.

## Scope and remaining limitations

This resolves the observed initialization/alias gap without weakening the LTL,
but is not a proof of four-state equivalence for arbitrary RTL. The rail audit
stops at state boundaries. Existing async-to-sync lowering, conservative Z-as-X,
noncontention candidate restrictions, and conservative prehistory handling still
bound the auxiliary model's applicability. Unsupported constructs fail closed;
native replay remains the acceptance authority. A timeout is a recorded solver
outcome, not evidence of either reachability or unreachability.

Reproduction (inside `nix develop`, using `experiments/haven-python` for the
HAVEN Python environment):

```sh
PYTHONPATH=experiments python -m unittest discover -s experiments -p 'test_*.py' -q
mill zaozi.tests.testOnly me.jiuyang.zaozitest.SVASpec
python experiments/smoke_encoded_aliases.py --out /path/to/new/diagnostic --yosys /path/to/yosys
```

Real-goal replay uses `experiments/encoded_pending.py` and the saved job manifests
`initialization-replay-jobs-20260914.json` / `initialization-replay-jobs-20260914-v2.json`
in `/var/storage/workspaces/clo91eaf`, with `--boundary frozen-source` and the
unchanged `rvprobe16-helpers-20260914-v1` source batch. Only UART's second
diagnostic changes `jg_time_limit` to `600s`.
