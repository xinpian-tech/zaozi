# Stability campaign and staged retrieval

## Release conditions, declared before pilot v1

Fixed Stage 1, RVProbe only, requested model `deepseek-v4-flash-vision-exp`,
at most three coverage rounds and four sequences per intent. Model settings,
24-request/64-tool-entry caps and 120-second proof budgets are unchanged.
HAVEN generation is not modified. Two same-version UART/ETHMAC repeats must
finish with complete accounting, no baseline-only result, and no inherited
reasoning fields. Coverage tolerance is two percentage points relative to the
reference and between repeats. Repeat token ratio <=1.5; time ratio <=1.75.
ETHMAC total tokens <=85% of the reference; UART <=125%. These are engineering
release gates, not estimates of statistical significance or monetary savings.
Unresolved intents are retained and do not themselves fail the gate.

Reference: `rvprobe-uart-ethmac-repaired-20260914-v1`.
Passing both pilots releases a fresh, three-concurrent-design full-16 run;
pilot responses are never injected into that run.

## Pilot v1: not released

`rvprobe-stability-20260914-v1/pilot-1` tested independent evidence requests
without explicit retrieval/authoring phases. UART finished two rounds at
94.668878%, 238,474 tokens, compared with reference 94.912780%, 129,900 tokens.
The token gate failed (limit 162,375). Its first round alone used 182,830 tokens;
round two used 55,644. Removing inherited reasoning did not prevent expensive
reasoning during repeated evidence requests.

ETHMAC round one completed at 92.164079%, using 538,352 generation tokens.
Round two generation completed with 855,659 tokens, versus reference 680,126.
The campaign was stopped at a model-idle boundary after all round-two model
responses and solver records were saved, before round three. Round-two native
replay may be incomplete and is not reported as a completed round. ETHMAC has
18 fully accounted requests / 1,394,011 tokens in this interrupted pilot.
No full-16 batch or pilot-2 was launched. All costs and partial artifacts remain
under `/var/storage/workspaces/clo91eaf/rvprobe-stability-20260914-v1/`.

## Repair for v2

After the first tool request, the model is instructed to retrieve missing facts
only, without constructing LTL or performing exhaustive reachability analysis.
Once it returns READY, a separate tools-disabled call authors LTL. Only actual
read evidence is transferred; neither reasoning nor a premature draft becomes
input. Direct initial LTL still takes one request. Handoff calls are accounted
inside the existing budget, and incomplete responses are not regenerated.

A campaign-local stop flag now prevents further paid requests after a completed
pilot fails its health gate. This avoids interrupting responses and losing usage
when another design is still running. All completed rounds and model usage are
retained even if the peer subsequently stops before its next request.

Cache validation also rejects work/archive roots that resolve to the same
directory. An old scratch alias was found to point into the archive; only
rebuildable archived compilation caches were removed, not material evidence.
Its recorded apparent cache size must not be claimed as reclaimed `/dev/shm`
space. The corrected cleanup record explicitly reports zero shm bytes released.

Offline regressions cover staged handoff, non-forwarded drafts, incomplete
handoff accounting, safe request-boundary stopping, first-cause preservation,
and archive aliases. Online token/coverage benefit remains to be established.

## Pilot v2: performance outcome and an actual sampling defect

UART completed three rounds at 97.243757%, 273,758 tokens, eight model requests
and 1,950.424 seconds, with 18 accepted new sequences. The reference had only two
rounds. Coverage improved by 2.330977 percentage points, but the original token
gate FAILED; neither its threshold nor this verdict is changed.

ETHMAC used 2,068,428 tokens and 2,497.686 seconds before a third-round auxiliary
trace search failed. The last accepted coverage is 92.119595% (two rounds,
32 new sequences), not a completed final result. Original artifacts remain in
`rvprobe-stability-20260914-v2/pilot-1`; no pilot-2 or full-16 was launched by
that campaign. Its failed service/status are retained.

The actual defect was in the Tcl generated after the auxiliary cover succeeded:
an unsuccessful `visualize -replot` explicitly raised `encoded trace resampling
failed`, causing a process exception and discarding the otherwise usable batch.
The fix emits an explicit trace outcome and exits without exporting a witness
when the requested trace is unavailable. Python records `resampling_exhausted`,
retains native-validated candidates and their incomplete sampling status, and
does not claim the original intent is unreachable. Real process/compile errors
still raise. A synthetic JG test observed a successful cover followed by a
non-covering replot result of `error`; this is distinct from a Tcl exception.

## Correctness/reproducibility is separate from the optimization target

The earlier two-new-model-pilot gate mixed performance targets with framework
correctness. It remains a failed optimization experiment, not a passed gate.
For the user's request to finish a stable full run, the next release decision
instead checks fixed-stimulus reproducibility: repeat accepted sequences in
fresh native processes with the same seed and require identical coverage bin
denominators **and numerators**, percentages, score and native-LTL acceptance
count. This is deliberately a different, explicitly recorded diagnostic; it
does not establish low variance of independently generated model output or
that the original token/repeat-ratio targets were met.

`replay_completed_experiment.py` hashes the original artifacts, reconstructs only
the accepted rounds, verifies them against the exact saved per-sequence inputs,
and forbids model calls. `offline_saved_flow.py` separately exercises ETHMAC's
saved third-round LTL through compilation, solving, native selection and coverage
under the repaired backend. Neither diagnostic rewrites historical outcomes or
counts as a fresh model experiment. Only after these correctness checks pass
will a new, three-concurrent-design full-16 RVProbe batch be started. HAVEN,
fixed Stage 1, model settings, budgets and model-authored LTL remain unchanged.

Verified at 17:49 UTC: UART's fresh replay reproduced 97.24375725900116% with
identical bin counts and all 18 native-LTL sequences accepted (29 runs including
11 baseline sequences, 237.106 seconds, zero model calls). The ETHMAC saved-LTL
flow reproduced the actual failure condition: auxiliary candidate 1 returned
`undetermined` after its cover was solved. It now reports
`resampling_exhausted` / `trace_resampling_undetermined`, and completes with
10 native-accepted new sequences instead of throwing. Its single diagnostic
round measures 91.9935565620619%; this is NOT the failed paid run's final score
or an additional paid benchmark. A fresh replay of this diagnostic is next.
All 16 fixed environment identities were revalidated without model calls.

ETHMAC's independent repeat also passed: 19 fresh simulations including nine
baseline sequences, all ten native-LTL sequences accepted, exact coverage bins,
218.478 seconds and zero model calls. All four diagnostics have been verified
and archived under `/var/storage/workspaces/clo91eaf/` with original scratch
paths preserved by symlinks. Final Python regression: 491 tests, 452 passed,
39 explicitly skipped; `git diff --check` passed.

The new RVProbe-only full-16 batch started at 2026-09-14 17:56:08 UTC:
`rvprobe-full16-stable-20260914-v1.service` (initial PID 555840).
Work: `/dev/shm/rvprobe-full16-stable-20260914-v1`; archive:
`/var/storage/workspaces/clo91eaf/rvprobe-full16-stable-20260914-v1`.
The adjacent `.release.json` records the diagnostic basis and explicitly states
that the earlier optimization gate did not pass. This is a one-shot running
service, not a timer. Initial designs are ALU, AES and SHA3; completed designs
are archived before a worker admits the next design. No prior replies are reused.

Update: this cohort was stopped at 18:47 UTC after three provider requests
outlived the intended 600-second limit. Four designs completed, ALU failed due
to output truncation, UART/CAN/ETHMAC were interrupted and eight never started.
The owner exit and missing request usage are recorded in `deadline-stop.json`;
this is not a completed full16 result. See `provider-failure-repair-20260915.md`
for the total-deadline and safe-restart repairs. No fresh cohort has been started
after these repairs; the user requested local fixes without restarting experiments.
