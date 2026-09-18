# Provider failure handling repair — 2026-09-15

Scope: local framework changes and offline unit tests only, as requested. No
DeepSeek request, experiment service, EDA experiment or new full cohort started.
HAVEN, frozen Stage 1, model choice, prompt and inference budgets are unchanged.

## Evidence and boundary

The 2026-09-14 full16 v1 cohort stopped with four completed designs, ALU failed,
three interrupted designs (UART, CAN, ETHMAC) and eight not started. Its original
records remain untouched in
`/var/storage/workspaces/clo91eaf/rvprobe-full16-stable-20260914-v1`.

ALU round 3 returned `finish_reason=length`: 65,536 completion tokens, all
reported as reasoning tokens, with no usable answer. This is not a compiler
defect; the framework cannot reconstruct an answer that was never returned.
The new metadata distinguishes reasoning-budget exhaustion from other output
truncation, but does not claim to eliminate model truncation.

The interrupted requests exposed a real framework defect: a 600-second socket
timeout did not bound the whole request lifetime. The previously implemented
total wall-clock deadline is retained and verified with a loopback HTTP server
whose response trickles data. Provider availability/recovery is not established
by these offline checks.

## Additional fixes

- Transport exceptions no longer automatically resend a possibly billable task.
- Explicit transient HTTP errors may retry within the existing budget only
  before any completed dialogue step; follow-up errors do not restart retrieval.
- Resume refuses a saved request without a reusable response, including stale
  running, failed and successful request records. Existing complete responses
  can still be reused by the offline workflow.
- Provider terminal outcomes carry a failure kind and prohibit model-source
  repair. Truncated tool/handoff responses follow the same boundary.
- Known usage is retained; unanswered requests remain unknown, not free.

Validation: full Python regression ran 500 tests, 461 passed and 39 skipped;
`git diff --check` passed. Fixtures cover total deadline, socket failure, stale
resume, paid tool-follow-up failure, numeric truncation classification and
unchanged successful responses. No live success or full16 stability is claimed.
