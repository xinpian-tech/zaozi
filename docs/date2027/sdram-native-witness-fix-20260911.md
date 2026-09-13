# SDRAM: formal candidates versus concrete witnesses

Diagnostic only, no provider calls. The unchanged manually authored UT has SHA256
`c7130f92e7a2ac5e9e7d1f443bcceed59dbe4c546ead84a7b2d783ae6263d7f6`.
It is not a DeepSeek benchmark and is not added to RAG, the skill, or model examples.

## Reproduced cause

The original read-data witness fails even with VCS `+delay_mode_zero`. Removing
delays is neither the fix nor a production configuration change.

The live hierarchy dump is in
`/dev/shm/rvprobe-sdram-waveform-20260911-v1/sequence-4/dut.vcd`.
The exact original solver trace is in the previous manual diagnostic's
`paired/rvprobe/round-1/generation/attempt-1/solve/external_read_data_reaches_wishbone/jg/witness.vcd`.
The witness begins at simulation time 315001 ps. At formal time 5350, corresponding
to simulation time 1385001 ps, external `sdr_dq_enable=0`, the controller is not
driving (`sdr_den_n=3`), and `app_rd_valid=1`. JG's pipeline and `app_rd_data` contain
`0xa55a` / `0xa55aa55a`; the live data pipeline and `app_rd_data` are unknown.
At 1407001 ps, `wb_ack_o=1` but native `wb_dat_o` remains unknown, so the original
Cover correctly does not hit. Input/reset/event replay fidelity passes.

This is an existential two-state solution exploiting floating-bus values, not a
faithfully reproducible four-state simulation witness. Uninitialized outputs also
differ early in the trace; those differences alone are not grounds for rejecting
a goal that does not observe them. The native original Cover remains the oracle.
Cadence's installed 2021.03 platform guide (Visualize, pp. 176–177) documents that
floating and contending buses can display concrete values assigned by the engine.

## Framework changes

- Classify a missing native Cover hit even when its error follows a complete VCS
  log. The former `startswith` check incorrectly permitted a paid model repair.
- Validate replay input/reset/timing samples before checking the Cover hit, so an
  adapter defect cannot be mistaken for a non-concrete formal solution.
- For the independent-DUT paired path, treat formal witnesses as candidates.
  Validate each on live IO; if needed, resample the **same** cover using existing
  soft input preferences. Keep its RTL, UT, assumptions and trace horizon fixed.
- Require the complete requested count (normally four) of distinct native-valid
  sequences per generated intent. Budget at most four times that target, capped
  at 256 distinct candidates. Exhaustion fails closed, without accepting a partial
  intent or asking the model to replace it.
- Only an original-Cover miss is eligible for another witness. Compilation,
  transport, timeout and unknown failures are not swallowed by this search.
- Reuse verified single-sequence cache entries in the final cumulative coverage
  merge. Record all rejects, solver artifacts, selection time and attempt counts.
  No coverage-gain selection is performed.

HAVEN's prompt, Stage-1, DUT and LTL are unchanged. This changes RVProbe's internal
concretization algorithm, not the shared environment or accepted coverage oracle.
Its extra solver/simulation time is part of RVProbe's cost; it is not free work.
Old failed samples are still failures and are retained.

## Validation

Offline search `/tmp/rvprobe-sdram-native-search-20260911-v1` generated 16 candidates
and tested 14 to obtain four original-Cover passes (indices 2, 4, 12, 13), retaining
ten rejects. Elapsed time 443.807 s; remote LLM requests zero. The target was not
changed, no additional assumptions were introduced, and no failed candidate was
relabeled successful.

The integrated manual-transport rerun **passed** at
`/tmp/rvprobe-author-sdram-fixed-20260911-v1`: one completed round, two intents,
eight original-Cover-validated sequences. Initialization accepted 4/4 candidates;
read data accepted 4/14 with ten retained rejects. One unchanged UT completion,
zero remote calls, no model or runtime repairs. Elapsed wall time 648.578 s
(07:37:43.622–07:48:32.199 UTC), including waiting for the manual response.
Diagnostic composite coverage: 86.398467%; not a DeepSeek benchmark comparison.
The final coverage union contains one fixed baseline and eight new sequences.
Exactly 19 single-sequence builds were needed (baseline + 18 candidate checks):
the final union reused the eight validated candidates without recompiling them.
All accepted replay artifact hashes and final coverage artifact hashes verified.

CAN regression `/tmp/rvprobe-can-native-regression-20260911-v1`: 8/8 original
sequences pass. CAN negative regression `/tmp/rvprobe-can-native-negative-20260911-v1`:
forcing `wb_cyc_i=0` causes all four previously passing mode-register sequences to
be rejected by their original Cover, as required.

Python regression: 328 tests, 312 passed and 16 environment-dependent skips.
Durable byte-verified archives and the integrated run's framework source snapshot:
`/var/storage/workspaces/rvprobe-native-witness-fix-20260911-v1`.
These results do not increment the formal 16-design paired completion count.
