# RVProbe token-targeted final audit — 2026-09-17

## Result

The optimized RVProbe cohort meets the fixed acceptance target on all 16 designs:
each cumulative RVProbe token total is at most 1.3 times the corresponding archived
HAVEN total. Failed and truncated provider requests are billable and are retained in
the totals. All 42 accepted coverage rounds passed native four-state LTL replay.

| Design | HAVEN tokens | RVProbe tokens | Ratio | Calls | Valid rounds | Final coverage % | Replay | Result |
|---|---:|---:|---:|---:|---:|---:|---|---|
| ALU | 142150 | 48913 | 0.344x | 3 | 2 | 93.955039 | all passed | pass |
| AES | 116396 | 78477 | 0.674x | 5 | 2 | 95.828734 | all passed | pass |
| SHA3 | 52533 | 52543 | 1.000x | 5 | 2 | 99.993495 | all passed | pass |
| AXIL_RAM | 28869 | 29265 | 1.014x | 2 | 2 | 92.944406 | all passed | pass |
| UE_TIMER | 129687 | 70876 | 0.547x | 4 | 3 | 99.627193 | all passed | pass |
| UART | 277265 | 133517 | 0.482x | 7 | 3 | 94.668878 | all passed | pass |
| CAN | 844655 | 157846 | 0.187x | 6 | 3 | 97.199577 | all passed | pass |
| ETHMAC | 1232803 | 165914 | 0.135x | 7 | 3 | 95.229430 | all passed | pass |
| I2C | 101302 | 63540 | 0.627x | 4 | 2 | 90.430522 | all passed | pass |
| GPIO | 117884 | 113215 | 0.960x | 6 | 3 | 86.038007 | all passed | pass |
| Simple SPI | 141938 | 107318 | 0.756x | 6 | 3 | 84.028893 | all passed | pass |
| SPI | 160029 | 111492 | 0.697x | 6 | 3 | 94.225759 | all passed | pass |
| UE_GPIO | 132387 | 88614 | 0.669x | 4 | 3 | 68.408087 | all passed | pass |
| UE_SPI | 165846 | 105437 | 0.636x | 4 | 3 | 78.024375 | all passed | pass |
| UE_UART | 146464 | 120601 | 0.823x | 6 | 3 | 88.361490 | all passed | pass |
| SDRAM | 367999 | 50907 | 0.138x | 2 | 2 | 87.978927 | all passed | pass |
| **Total / mean coverage** | **4158207** | **1498475** | **0.360x** | **77** | **42** | **90.433926** | **all passed** | **16/16** |

The aggregate ratio is a ratio of token sums. The unweighted mean of the 16
per-design ratios is 0.606x. This audit establishes the token target and accepted
trace validity; it does not establish coverage parity with HAVEN. In particular,
the optimized cohort's mean composite coverage is 90.434%, while the archived
HAVEN reference mean is 92.681%. Coverage and token totals must therefore both
remain visible in paper reporting.

## Accounting exceptions retained

- AES: 47,792 tokens and three calls from the failed pre-resume run plus 30,685
  tokens and two calls from the successful saved-response continuation. The Java
  `SIGBUS` failure is not excluded from cost.
- GPIO: 23,313 tokens and one truncated request plus 89,902 tokens and five calls
  from the successful budget-fix run. The cumulative record is
  `gpio/flow/paired-budgetfix-v2/cumulative-costs.json`.
- No failed request, feedback repair, cache-hit input token, or reasoning token is
  subtracted from any design total.

## Sources

- HAVEN reference: `/var/storage/workspaces/clo91eaf/rvprobe-usage-audit-20260916-v1/usage-detail.json`, `method=haven`, `cohort=original`.
- Main RVProbe cohort (14 designs): `/var/storage/workspaces/clo91eaf/rvprobe-token-targeted-14-20260917-v1`.
- AXIL_RAM and SHA3: `/var/storage/workspaces/clo91eaf/rvprobe-token-targeted-low-20260917-v2`.
- AES selected result: `aes/flow/paired-resume-v2/summary.json`, with the failed
  `aes/flow/paired/summary.json` added to cumulative cost.
- GPIO selected result: `gpio/flow/paired-budgetfix-v2/summary.json`, with
  `cumulative-costs.json` used for cumulative cost.

## Framework fix under test

The first `tool_choice=auto` request may either retrieve RTL evidence or directly
author final LTL. It now receives the larger of the retrieval and final-authoring
budgets, while retaining the low retrieval reasoning setting. This prevents a
valid direct answer from being truncated at the smaller retrieval cap. The focused
incremental-dialogue suite passes all 10 tests, including the new direct-answer
budget regression.
