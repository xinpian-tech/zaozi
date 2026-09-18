# JG compilation and goto repetition repair — 2026-09-14

## Scope and invariants

Offline runs reuse the saved DeepSeek LTL from
`rvprobe-failed5-backend-20260914-v2`. There are no new model requests or tokens.
Neither DUT RTL, frozen stage-1, original intent, nor HAVEN is modified. Native
Cover acceptance remains mandatory. These are diagnostics, not replacements for
historical paid results or a new complete 16-design cohort.

Durable artifacts:
`/var/storage/workspaces/clo91eaf/jg-compilation-goto-repair-20260914/`.

## UART overrun: compilation was mistaken for a proof outcome

The old log contains `EOBS002`, a property compilation timeout at 5000 ms.
Installed JG help confirms a default property compilation limit of 5 seconds,
separate from proof time. Set both property and task compilation limits before
elaboration, explicitly using the requested solve budget. Apply this consistently
to the native solver, witness sampler, and known-state fallback.

The native solver now raises structured execution errors for compilation timeout,
failed process, missing selected-goal status, and incomplete trace export. The
trusted experiment adapter preserves the error and does not ask the model to
repair framework failures. Actual proof timeouts remain unresolved outcomes.

Saved-response rebuild `rvprobe-uart-compile-repair-20260914-v1` compiled the
overrun property successfully. Proof then reached the genuine 120-second limit.
Thus the compilation defect is fixed, but this intent is not yet satisfied.
This process started before the final `solver_time_limit` detail prefix was added;
its unchanged JG log is the evidence for the proof timeout.

The complete four-intent offline UART round accepted the framing-error and modem
status witnesses (one each), rejected the empty-read candidate, and recorded the
overrun intent as unknown. Status: `completed_with_shortfalls`, not all intents
satisfied. Baseline coverage 90.933898%; accepted-sequence union 94.348365%;
elapsed 458.232 seconds. This cap-one diagnostic must not be compared as an equal
sequence-budget replacement for the earlier cap-four result.

## ETHMAC: native goto repetition semantics

The fallback parser rejected the original `[->16]` in
`tx_fetch_starvation_underrun_drives_phy_tx_error`. It now supports Boolean
`[->N]` and bounded `[->M:N]`, rejecting malformed bounds or sequence operands.
The positive observation is encoded as `value && !unknown`, and the native goto
operator is retained around it.

An initial expansion requiring known-false skipped cycles was disproved by the
real VCS regression: native goto may skip X, although X cannot count as a true
observation. The failed v1 diagnostic is retained. Corrected
`rvprobe-goto-semantics-20260914-v2` checks an X-containing false-positive control,
X-skipping with enough known-true observations, and a fully known trace. Original
and encoded Cover agree; an unsafe encoder counting X is caught by intermediate
assertions. The smoke requires its explicit pass receipt, not just process exit.

The original ETHMAC goal now analyzes, elaborates, and reaches proof. Direct
120-second diagnostic `rvprobe-ethmac-goto-repair-20260914-v1` ended undetermined
after 160.300 seconds total. It did not produce an accepted witness.

Full saved round-two diagnostic `rvprobe-ethmac-round2-repair-20260914-v1`
finished in 426.636 seconds. Three of four intents passed original native Cover:
BD readback, zero-select Wishbone error, and RX DMA. RX DMA is a real fallback
success: the original candidate failed live-IO LTL acceptance, the known-state
candidate was covered, and its original native replay passed. TX starvation
remained unresolved at the configured budget. The round reports
`completed_with_shortfalls`, three accepted sequences, baseline 84.177855%, and
accepted-sequence union 88.475278%. It starts from the fixed baseline, not the
historical round-one accepted-sequence union, so this score is not an updated
closed-loop final score.

## UART empty-read search

The original two-state witness reads an unwritten FIFO slot as `0xff`; native
RTL returns X. Reset clears pointers/count but not FIFO memory. Do not initialize
the DUT artificially, relax the intent, or accept this witness. See
`uart-empty-read-20260914.md` for waveform evidence.

Additional Mp and G2 runs use the same saved goal with explicit 600-second proof
budgets. This is offline solver diagnosis; production engine selection remains
`auto`. Engine selection and budget are recorded with the diagnostic. A covered
auxiliary goal still needs original native replay before it can count as solved.

Mp diagnostic `rvprobe-uart-known-mp-20260914-v1` reached the 600-second
proof limit, total 609.717 seconds, status `undetermined`; no accepted witness.
G2 diagnostic `rvprobe-uart-known-g2-20260914-v1` likewise reached the
600-second proof limit, total 608.647 seconds, status `undetermined`. Both
processes closed normally. Neither proves the intent unreachable.

## Final outcome

All diagnostics have ended. The two identified framework defects (property
compilation configuration/error classification and unsupported goto repetition)
are repaired and exercised with real tools. UART has two of four saved intents
native-validated; ETHMAC has three of four. Three intents remain unresolved:
UART empty-read and overrun, and ETHMAC TX starvation. They are recorded as
bounded solve shortfalls, not accepted witnesses or complete intent success.
No new paid experiment was run, and the historical 14-of-16 completed-design
count is not advanced by these offline checks.

## Regression

Python: 467 tests, 428 passed and 39 skipped. Scala JasperGoldTest: 5 passed.
Native VCS goto semantic regression: passed. `git diff --check`: clean.

The initial Python run hit the production disk-space guard because `/tmp` is on
the nearly full root filesystem. Re-running with a task-specific `/dev/shm`
TMPDIR passed; the guard was not weakened. Closed artifacts are copied and
verified before replacing their scratch directories with archive links.
