# Runtime path repair and offline source-entry verification

The paid `rvprobe-failed5-backend-20260914-v1` batch failed before solving:
`ut_harness.py` still fingerprinted `experiments/ut_validation.py`, which had
been moved to `rvprobe/backend/validation.py`. This was a framework defect,
not invalid model-authored LTL. The old offline checks started from compiled
goals and therefore missed this source-entry failure.

The input-check error was incorrectly eligible for model repair. Original costs
remain unchanged: ALU 113,819 tokens / 7 requests; simple_spi 120,922 / 6;
SPI 91,659 / 5. Total: 326,400 tokens / 18 requests. All three failed in round 1;
UART and ETHMAC were never dispatched. These results are not replaced by the
offline checks below.

## Changes

- Runtime paths follow the imported backend. Required files are checked and
  hashed before a model request, as well as inside the compilation harness.
- Model repair uses a positive whitelist: malformed LTL response/envelope and
  compilation errors explicitly attributed to `model.ltl`. Filesystem,
  initialization, toolchain, lowering, wiring, solver and unknown errors stop;
  they do not request another LTL response.
- The offline smoke entry can rebuild the saved response through source
  assembly, sandbox compilation, solving, sampling and production replay.
  A no-model guard surrounds this path. No historical LTL is edited.
- Batch ownership records PID, process start ticks and boot identity. A status
  reader detects an absent/reused owner and recovers terminal child records.
  Explicit reconciliation preserves the original progress file.
- A one-shot systemd launcher decouples future long runs from tool sessions.
  This is not a timer. Its independent launch smoke exited successfully.

The old parent process was absent, with a stale `running` record. Its exact
exit cause is not established. The reconciled batch is `interrupted`, with
three failed designs and two `not_started` designs, not a completed batch.

## Offline verification

Each run used attempt 3's unchanged saved response, all four intents, and a cap
of four sequences per intent. Fixed Stage 1 was verified before and after.

| Design | Accepted sequences | Elapsed seconds | New model tokens / requests |
| --- | ---: | ---: | ---: |
| ALU | 16 | 205.6 | 0 / 0 |
| simple_spi | 16 | 228.7 | 0 / 0 |
| SPI | 16 | 179.7 | 0 / 0 |

These are offline framework recovery checks, not fresh model experiments or
full multi-round coverage comparisons. HAVEN was not rerun or changed here.
Artifacts are archived beneath
`/var/storage/workspaces/clo91eaf/runtime-path-repair-20260914/`.
Original failed experiments remain beneath
`/var/storage/workspaces/clo91eaf/rvprobe-failed5-backend-20260914-v1/`.
Verified scratch copies are replaced with links; source, witness, log and
accounting evidence is retained, while rebuildable simulation caches are omitted.

Python regression: 458 tests, 419 passed and 39 optional tool tests skipped.
The three real-tool source rebuilds above were run separately.
