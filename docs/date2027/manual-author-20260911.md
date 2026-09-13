# Manual completion diagnostic — 2026-09-11

Follow-up: the same SDRAM UT now completes the integrated flow after native
witness-selection repair. See [root cause and verified rerun](sdram-native-witness-fix-20260911.md).
The original failed attempts and measurements below remain unchanged.

User requested no DeepSeek calls: author complete LTL responses locally and run
the real pipeline. These results are NOT DeepSeek benchmark measurements.

The existing `manual_flow.py` substitutes only the completion transport, with
request-hash-bound mailbox responses. Provider calls and credentials are disabled.
The manual entry now selects the same `independent-dut-v1` boundary as new paid
batch entry points. Fixed Stage-1/RTL, compilation, JG, sampling and replay remain
real. One round, four sequences per intent, no automatic runtime repair.

| Design | Complete UT intents | JG results | Native original-Cover replay |
|---|---|---|---|
| CAN | Mode-register write/read; acceptance-code write/read | 2/2 generated | 8/8 pass; round completes |
| SDRAM | Initialization observed; explicit external read data reaches Wishbone | 2/2 generated | 5/8 pass; 3 read-data samples fail, entire round rejected |

Each design needed one manual completion and one compilation attempt. No syntax
repair, LTL replacement, added Assume or modified DUT behavior. SDRAM's read-data
intent drives the external split data pins explicitly: it tests controller data
transfer, not external memory persistence or the old write-then-readback intent.

Elapsed diagnostic wall time includes baseline preparation and waiting for manual
responses: CAN 303.24 s, SDRAM 261.71 s. Remote LLM requests: 0. Author token usage
is unavailable and must not be recorded as zero or compared against DeepSeek.

Source UTs are under `out/experiments/manual-author-20260911/{can,sdram}/ModelUT.scala`.
Working roots are `/dev/shm/rvprobe-author-{can,sdram}-20260911-v1`.
Durable archive is `/var/storage/workspaces/rvprobe-author-20260911-v1`; it preserves
mailbox prompts/responses, compiled UTs, solver witnesses, samples and replay logs.
These diagnostic DUT answers must never enter framework RAG or skill examples.

## Conclusion and next action

Both designs can compile and produce JG witnesses with locally authored LTL. CAN
also completes this diagnostic replay, but SDRAM still reproduces a formal/native
replay mismatch without DeepSeek. This does NOT prove the framework is complete
or resolve the previously failing provider-authored intents. Inspect the three
SDRAM read-data samples and the old CAN failure; keep the exact UTs and outputs.
Do not launch DeepSeek as a fallback to these diagnostics.
