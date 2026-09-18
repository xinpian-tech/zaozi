# Preserve per-intent outcomes without weakening native acceptance

## Defect

In UART's saved round 1, the first intent's two-state witness depends on an
unwritten FIFO slot. Its native Cover fails and the known-state solver reaches
the 120-second limit. That bounded search result previously raised an
infrastructure-style exception, aborting the entire round before later
independent intents were checked. Previously validated prefixes could also be
lost from the returned candidate batch.

This is distinct from the semantic issue documented in
[UART empty read](uart-empty-read-20260914.md). The invalid first witness must
remain rejected, and neither unreachability nor a successful replacement has
been established for that intent.

## Changes

- Selection returns its native-validated subset plus an explicit `exhausted`
  record if the bounded target was not reached. It never returns failed inputs.
- Check the already available native pool before escalating to known-state
  solving; one rejected candidate does not discard other available candidates.
- The backend processes subsequent independent intents, recording unresolved
  and partial outcomes. Validated sources/schedules are checkpointed.
- Infrastructure failures, unsupported encoding and unknown errors remain
  fatal. The native receipt, input fidelity and original Cover checks are intact.
- Closed-loop coverage uses only accepted sequences. Summary completion is
  distinct from `all_intents_satisfied`; an empty round records no coverage gain.
- An offline coverage switch exercises the fixed baseline and real VDB union.

No DUT, LTL, skill, model prompt, Stage 1, HAVEN generation policy, or default
solver budget is changed. Original paid results are not overwritten. Native
backend/selection policy identifiers advance to v2 to make the behavior change
explicit in future experiment provenance.

## Verification

Unit regressions cover failed-first/valid-later intents, retained valid subsets,
zero accepted sequences with unchanged coverage, and genuine infrastructure
failure preserving earlier receipts without continuing. Real-tool verification
uses all original UART round-1 goals, four sequences per intent maximum and the
original 120-second auxiliary budget, with no model calls. Its detailed outcome
is recorded separately from the historical model experiment.

The real UART run processed all four original intents without changing their
LTL. Framing-error read/clear and modem-status delta read/clear each produced
four native-validated sequences. The FIFO empty-read intent remained unresolved
after the 120-second auxiliary limit; the overrun intent retained its original
`unknown` solver result. Neither contributes a sequence or coverage. Thus there
are eight accepted sequences but **not** four satisfied intents.

Python regression: 466 tests, 427 passed and 39 optional tool tests skipped.

The offline run finished in 466.5 seconds with `completed_with_shortfalls` and
`all_intents_satisfied=false`. Eight native Cover receipts enter the real VDB
union. Composite coverage improves from 90.9339% to 94.6689%; denominators are
unchanged: line 84, condition 78, toggle 410, branch 56. New model requests/tokens
are both zero. This is a one-round saved-LTL framework check, not a fresh paid
multi-round experiment and not a full-intent success result.

Artifacts:
`/var/storage/workspaces/clo91eaf/intent-shortfall-isolation-20260914/rvprobe-uart-intent-isolation-20260914-v1/`.
The original failed paid run and its costs remain unchanged. The archived
diagnostic retains its shortfall status; an IO-only checkpoint is used to verify
and release the closed scratch copy, with its original path preserved as a link.
