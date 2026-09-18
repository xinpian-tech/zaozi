# Targeted-evidence token pilot — 2026-09-17

## Status and acceptance

Completed `rvprobe-token-targeted-20260917-v1.service` for UART and CAN, two concurrent designs.
This is an engineering pilot, not evidence that the 16-design token goal is achieved.
All requests, including failures, count. Cache hits remain input tokens; reasoning is already
included in output tokens. A failed/empty run is not an optimization success merely because
it used fewer tokens. Report last valid coverage (baseline when none) alongside valid rounds.

The current acceptance target is **RVProbe at most 1.3x the corresponding HAVEN token total
for every design**, not only for the cohort average. It is neither 50% nor 1.5x of HAVEN.
Integer thresholds are floored only because provider usage is integral.

| Design | HAVEN tokens | Target: at most 1.3x | Prior RVProbe tokens | Pilot RVProbe tokens | Ratio | Pilot coverage % | Result |
|---|---:|---:|---:|---:|---:|---:|---|
| UART | 277265 | 360444 | 398968 | 332914 | 1.20x | 95.2332931296346 | pass |
| CAN | 844655 | 1098051 | 2810263 | 2089303 | 2.47x | 97.81099033816426 | fail |

HAVEN CAN terminated with failure; its recorded last-valid coverage was 96.69384057971014%.
That limitation must remain visible in comparisons. HAVEN UART completed at 95.65526445099616%.
UART used five model requests and met the current token target. CAN used 21 requests and
missed it by 991252 tokens in this superseded pilot.
The dominant CAN cost was 1116712 reported reasoning tokens across repeated RTL evidence
requests. The pilot used `max` reasoning and the 393216-token response limit for retrieval as
well as final authoring, so a lower bounded retrieval configuration remains to be evaluated.

## Changes under test

- After a read, accept the model's final LTL directly instead of requiring READY followed by
  another independent authoring request. Preserve READY fallback and source validation.
- Request evidence for the current batch, not exhaustive gap/RTL traversal. All evidence is
  still accessible; coverage rows are not filtered by the framework.
- Shorten task-access boilerplate, keeping tool schemas, spec, IO, API semantics and examples.
- Explicitly delegate concrete witness search/input enumeration to the solver; preserve
  required handshake/history/output checks and original native replay acceptance.
- Refuse tool entries beyond the unchanged quota and reserve final authoring from evidence
  already obtained. Record nonexecution honestly, instead of losing the run immediately.
- Limit the initial diagnostic message excerpt; exact diagnostics stay available on demand.

The pilot combines these changes; it is not a single-factor causal ablation. No design answers
were injected, no Stage-1 regeneration, and no HAVEN changes or rerun. Do not change experiment
runtime files while this cohort is active.

## Frozen configuration

- Model: `deepseek-v4-flash-vision-exp`; reasoning effort `max`; temperature 0.3.
- Response budget: 393216 tokens including reasoning; request timeout: 3600 s.
- Three coverage rounds maximum; at most four intents per round and four sequences per intent.
- Same `staged` dialogue family, JG/native replay, raw independent-DUT boundary and auxiliary
  Yosys executable as the repaired/max reference; seed 20260906.
- Fixed environment: `/var/storage/workspaces/clo91eaf/fixed16-environment-20260912-v3/stage1-map.json`.
- Preflight verified 85 UART and 90 CAN Stage-1/source artifacts and 21 trusted runtime files.
- Regression: 564 tests run, 21 skipped, remaining passed; no provider calls in these tests.

## Artifacts and monitoring

- Work: `/dev/shm/rvprobe-token-targeted-20260917-v1`.
- Archive: `/var/storage/workspaces/clo91eaf/rvprobe-token-targeted-20260917-v1`.
- Log: `/var/storage/workspaces/clo91eaf/rvprobe-token-targeted-20260917-v1.service.log`.
- Each terminal design is archived and verified before its scratch directory is replaced by
  an archive symlink. Failure of archival retains the scratch data.
- Per-design final result: `DESIGN/flow/paired/summary.json`, arm `rvprobe`.
- Reference audit: `/var/storage/workspaces/clo91eaf/rvprobe-usage-audit-20260916-v1/usage-detail.json`;
  HAVEN uses `cohort=original`; RVProbe uses `rvprobe-fixed16-repaired-max384k-20260916-v1`.

While running, reported event usage is a subtotal, not the final experiment cost. Record new
results separately; do not overwrite the paper CSV or replace historical runs with selected
better samples. Evaluate both actual token reduction and coverage/validity before expanding.

## 1.3x candidate configuration and strict-budget results

The later candidate keeps the same fixed Stage-1, three-round ceiling, stopping policy,
four intents per round, four sequences per intent, JG solve, native four-state replay and
coverage measurement. It changes only RVProbe generation: append-only tool dialogue,
`low` final/retrieval reasoning, one optional evidence step, eight tool entries, a 12288-token
retrieval limit and a 16384-token final limit. The skill now instructs direct LTL authoring
when spec/IO/current feedback are sufficient and explicitly rejects `Reset` as a Bool operand.
HAVEN and its archived measurements are unchanged.

| Design | HAVEN tokens | 1.3x ceiling | RVProbe tokens | Ratio | Requests | Valid rounds | Final coverage % | Replay | Result |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| AXIL_RAM | 28869 | 37529 | 29265 | 1.014x | 2 | 2 | 92.94440631434455 | all passed | pass |
| SHA3 | 52533 | 68292 | 52543 | 1.000x | 5 | 2 | 99.99349466562582 | all passed | pass |
| CAN | 844655 | 1098051 | 160855 | 0.190x | 5 | 2 | 97.199577294686 | all passed | pass |

Artifacts:

- `/var/storage/workspaces/clo91eaf/rvprobe-token-targeted-low-20260917-v2`
- `/var/storage/workspaces/clo91eaf/rvprobe-token-targeted-can-20260917-v4`

SHA3 includes one first-round compiler-feedback repair for the invalid whitespace-infix
`##` spelling; its cost is retained. AXIL_RAM authored both rounds directly without RTL reads.
No failed request, repair request, cache-hit input or reasoning token is excluded from totals.
