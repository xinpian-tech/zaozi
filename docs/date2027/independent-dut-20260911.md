# Independent DUT boundary — 2026-09-11

User selected option 1: solve on the DUT and replay its exact input stimulus.
This is a new experiment boundary, not a reinterpretation of old failed runs.

## Implementation

- New runs through `haven_design_batch.py` and `haven_event_paired.py` default to
  `--boundary independent-dut-v1`. Use that explicit flag for saved-witness
  diagnostics through `recheck_native_ltl.py`.
- Frozen Stage-1 sources, RTL, baseline sequence sources and HAVEN's prompt
  template are unchanged. New shared bundles record the boundary explicitly.
- The formal environment retains the clock schedule, resets and static inputs.
  External pad/feedback constraints are removed from the independent pin boundary.
- In raw mode all data inputs come from the sequence's own storage. Native
  BFM/reactive outputs cannot replace them at the DUT instance.
- Native producers remain available for saved native sequences. The exact same
  raw pin interface is exposed to HAVEN via existing `randomize_send` constraints:
  `rvp_raw`, `rvp_reset`, `rvp_duration_ps`, `rvp_drive_<port>`, and
  `rvp_clock_<port>`. Soft defaults preserve selection of native mode for old
  sequences. No new HAVEN DSL operation or design-specific answer is injected.
- Both arms receive the boundary/API metadata. Additional randomized fields may
  alter the native RNG stream; new common baselines must be measured, not copied
  from old experiment coverage.
- Input checks run even in native-Cover mode. Input mismatches are infrastructure
  failures. A formal Cover that fails native replay also stops model repair:
  investigate input fidelity, initialization, clock sampling and the monitor.
- Existing LTL provenance checks and native four-state Cover admission remain.
  No failed LTL is weakened, no output is driven and no storage is zero-filled.

## Offline evidence (not provider-model experiment results)

No model calls or hand-authored experimental LTL. Reused the stored batch-j UTs
and input schedules in new output directories; original attempts are retained.

| Saved candidate | Independent replay result |
|---|---|
| I2C round-2-repair-1, all intents | 16/16 pass original Cover |
| I2C interrupt intent, request input forced low (diagnostic mutation) | 4/4 rejected by original Cover |
| CAN round-1-repair-1, acceptance-code readback | First witness still fails original Cover |
| SDRAM round-3-repair-1, write/readback | First witness still fails original Cover |

Working artifacts: `/dev/shm/rvprobe-independent-i2c-20260911-v2`,
`/dev/shm/rvprobe-independent-can-20260911-v1`,
`/dev/shm/rvprobe-independent-sdram-20260911-v1`.
These are diagnostic-only and must not increase the completed-pair count.
Durable copies (material files byte-verified, no working files deleted):
`/var/storage/workspaces/rvprobe-independent-20260911-v1`, including
`archive-audit.json`. Negative regression artifacts are included there too.

Full suite in the HAVEN/Nix environment: 321 tests, 305 pass, 16 skipped.
Includes a test that the unchanged HAVEN DSL generator retains all exposed raw
input constraints. Six batch-j frozen Stage-1/RTL identities reverified unchanged.

SDRAM's archived material was byte-verified before removing 2,526,820,066 bytes
of rebuildable compiler caches. No material experiment artifacts were deleted.
CAN cache pruning was refused because an archived URG `tests.txt` differed;
no CAN caches were deleted. Preserve both copies before resolving that archive.

## Remaining gates before paid experiments

1. Diagnose CAN and SDRAM's remaining native-Cover failures under exact inputs.
2. Validate native HAVEN baselines and common raw transport on affected designs.
3. Run fresh matched provider-model pairs under the new boundary; retain old
   results and costs separately, not as the same comparison cohort.
