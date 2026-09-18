# Independent evidence requests — 2026-09-14

## Defect and scope

The completed ETHMAC run used 38 distinct successful provider requests and
2,928,984 reported tokens. There were no provider truncations or source-repair
attempts. Input accounted for 2,753,298 tokens, including 2,607,872 cache-hit
tokens. Each tool continuation resent all preceding assistant messages, including
their reasoning fields. Large cross-round RTL history also switched abruptly
from full bodies to index-only at 48,000 serialized characters.

This repair changes RVProbe's evidence/request adapter, not HAVEN, DUT RTL,
Stage 1, LTL syntax, skill contents, native acceptance, or solver budgets. The
24 model-request / 64 tool-entry caps are unchanged. No new model call was made.

## Implementation

- Each tool batch is followed by an independent inference request containing
  the original skill/task and exact observed data. This is not deletion of a
  required field inside an ongoing provider tool protocol. Prior assistant
  messages and orphan tool replies are absent altogether.
- Observed RTL ranges are byte/hash-validated against the frozen sources and
  merged without adding unseen adjacent lines. Identical non-RTL observations
  are deduplicated. Original tool records, errors, and pagination remain intact.
- Large history carries an exact deterministic prefix of up to 48,000 text
  characters, a complete range index, and continuation offsets; it no longer
  loses every body at the threshold. No DUT-specific ranking or answer injection.
- A request may directly return LTL when evidence is sufficient. There is no
  mandatory extra authoring call on the direct-output path. The final budget
  slot is tools-disabled and equally free of prior assistant reasoning.
- Request packets and policy identifiers are recorded. Unexpected final tool
  calls preserve their names and accounting, not private reasoning content.

## Validation

473 Python tests: 434 passed, 39 optional tests skipped. Added regressions check
fresh request structure, unchanged direct-output path, exact source union,
forged-evidence rejection, Unicode partial-history continuation, duplicate
history/current reads, and retained errors/diagnostics. Existing tests still
cover usage accounting, bounded requests, truncation, and unchanged HAVEN entry.
`git diff --check` passed. No hardware-generation changes require a new Scala
compile for this patch.

`profile_evidence_dialogue.py` replays the historical ETHMAC tool boundaries
offline. The same reads and request counts are held constant. Measured sums of
input content plus reasoning **characters**, excluding schemas/tool arguments:

| Round | Requests | Historical | Clean requests | Reduction |
|---|---:|---:|---:|---:|
| 1 | 13 | 1,995,342 | 1,401,034 | 29.78% |
| 2 | 8 | 1,770,674 | 1,308,526 | 26.10% |
| 3 | 17 | 4,230,272 | 3,217,151 | 23.95% |

Artifact:
`/var/storage/workspaces/clo91eaf/evidence-dialogue-repair-20260914/ethmac-profile-final.json`.
Historical paid records are unchanged. The offline profile does not establish
token savings, price reduction, or preserved coverage: the fresh-request policy
can change reasoning generation, tool choices, and cache hits. Those require a
new controlled model experiment, and should not be inferred from character size.
