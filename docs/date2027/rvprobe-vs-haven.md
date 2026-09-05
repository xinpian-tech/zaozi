# rvprobe against HAVEN, in HAVEN's own terms (2026-09-04)

HAVEN's evaluation reports one number per design — the mean of the URG metrics its run produced — plus compile
success, tokens and wall-clock time, on GPT-5.2. Everything below is measured the same way, on the same testbench
per design (HAVEN's generated bench, both arms' stimulus spliced into it), scored by one pinned rule over the DUT
modules (`experiments/urg_score.py`; four metrics where URG infers no state machine, five where it does). HAVEN's
reproduction is on DeepSeek V4 Flash. Sources: `data/haven-deepseek-results.csv`, `data/alu-jg-loop.json`,
`data/i2c-flow.csv`, `data/sdram-flow.csv`, `data/can-flow.csv`, `data/ablation-2x2-samples.csv`.

## Coverage

| design | HAVEN published (GPT-5.2) | HAVEN reproduced (DeepSeek) | HAVEN reproduced, bench fixed | **rvprobe** | rvprobe FSM | HAVEN FSM |
|---|---|---|---|---|---|---|
| alu_top | 96.1 | 95.87 | — | **95.00** (DeepSeek/JasperGold loop, DUT-only) / 95.44 ± 0.9 (ablation plan/typed, n=4) | — | — |
| i2c_master_top | 90.0 | 75.15 | 75.15 | **87.50** | 48/48 | 32/48 |
| sdrc_top (sdram) | 70.0 | 11.02 | 70.59 | **71.03** | 24/41 | 23/41 |
| can_top | 90.6 | no compiling bench | no compiling bench | **50.47** | n/a (no FSM inferred) | — |

"Bench fixed" is HAVEN's own stimulus run after the defects in its generated bench were repaired and declared:
i2c — none needed for scoring (its driver never drives `arst_i`, which only limits it); sdram — the SDRAM clock
tied (`logic sdram_clk` was never driven) and the SDRAM-side agent's zeroing of all thirteen configuration pins
removed; can — the `wb_dat_o` field its own monitor and driver read added to the seq_item, after which its
LLM-written sequences still do not parse. HAVEN's i2c number under its own unpinned rule is 63.24; 75.15 is the
same report re-scored over the DUT modules with the FSM column included, the rule both arms use.

## HAVEN's own loop on repaired benches (2026-09-03, two passes each)

The "bench fixed" column above replayed HAVEN's *first-round* sequences. This section is HAVEN's own Stage 2 —
compile-repair, simulation, coverage analysis, three LLM/BO improvement rounds — rerun on DeepSeek from its own
Stage-1 sequences, on a copy of each run whose bench carries only the declared repairs (`rvprobe_fixes.txt` in every
copy; script and per-run logs in the session scratchpad; `data/haven-fixed.csv`). Two passes per design, because
the loop's gain turned out to depend on whether the sequences its model writes in that pass compile.

| design | repairs | HAVEN's own loop, pinned rule, pass 1 / pass 2 |
|---|---|---|
| sdrc_top | SDRAM clock tied; 28 driver assignments to cfg_*/sdram_clk removed | 70.57 / 70.50 (seqs 4->12, 4->8; rollbacks 1, 1) |
| i2c_master_top | driver drives arst_i from the item | 70.13 / 70.13 (seqs 12->24, 14->26; rollbacks 0, 0) |
| gpio | driver drives gpio_input_i from the item | 81.79 / 91.86 (seqs 9->9, 9->15; rollbacks 2, 1) |
| spi_lite | driver references the enum literal directly | 56.73 / 57.03 (seqs 24->28, 18->30; rollbacks 1, 0) |
| can_top | seq_item gets the wb_dat_o its template reads | no number (simulation timed out (300 s cap) on every attempt) |
| ethmac | MII clocks tied; wb_dat_o; one-argument uvm_subscriber constructor; a cross over an undeclared coverpoint removed | no number (code crashed (repair reply failed to parse, unguarded None) / repair loop exhausted) |

Reading the pairs: sdram and spi_lite reproduce within half a point; i2c reproduces exactly (identical three-step
progression) yet sits five points under the same design's 08-28 run, so the loop is consistent within a day and not
across days; gpio moves ten points between passes on one thing — in pass 1 both batches of newly generated sequences
failed to parse (the model emitted a control character) and were rolled back, in pass 2 one batch compiled. can
compiles once the field is there and then HAVEN's own simulation hits its 300 s cap on every attempt, both passes;
ethmac's repair loop crashed on an unparseable model reply in pass 1 (unguarded None in `fix_component`) and
exhausted its five attempts in pass 2. Neither was patched further: those are HAVEN's own failures, and the user's
decision was to leave them as such.

rvprobe was rerun end to end the same afternoon, on the same machine and benches, after the sweep: every witness
regenerated on JasperGold (the i2c and sdram witnesses came back cycle-identical; the refresh proof took 578 s
against 38 s and 295 s on earlier runs — the engine's timing varies, its answers did not) and every arm rescored
(`data/rvprobe-rerun.{csv,json}`): i2c **87.50** (48/48, unchanged), sdram **71.02** (24/41; 71.03 before), can
**50.47** (unchanged). Set beside HAVEN's two passes on the same benches: sdram 71.02 vs 70.57 / 70.50; i2c 87.50
vs 70.13 / 70.13; can 50.47 vs no number. The two methods differ in reproducibility as much as in coverage: a
solved intent replays the same way on any day; a generated sequence set is whatever the model wrote that pass.

## What produced the stimulus

| design | rvprobe intents | author | solver calls | solver time | replay | HAVEN sequences | HAVEN LLM calls | HAVEN tokens | HAVEN wall clock |
|---|---|---|---|---|---|---|---|---|---|
| alu_top | 10 bulk + 6 + 6 + 2 residual | bulk/6: author; residual: DeepSeek | 8 residual (JasperGold) | 51.1 s | 10,014 txns | 12 (4 spec + 2 rounds) | 14 | 168k | 1,051 s |
| i2c_master_top | 13 single-beat + 7 flows | author | 20 (circt-bmc, JasperGold) | 65 s (JG, flows) | 8,800 + 5,570 beats | 24 | 27 | 453k | 2,826 s |
| sdrc_top | 8 flows + fill | author | 8 (JasperGold) | 484 s | 149k sim cycles | 18 | 24 | 463k | 2,843 s |
| can_top | 2 flows (+3 recorded) | author | 5 (JasperGold) | 20 s (+ 3 × 300 s undetermined) | 13 txns, 30k cycles | 6 (do not compile) | 17 | 290k | 1,616 s |

Two things this table must say plainly. The i2c, sdram and can intents were written by the author, not by a model;
HAVEN's were written by a model. The LLM-in-the-loop measurements on rvprobe are the ALU ones: the current
JasperGold residual loop (two calls, eight candidate intents, 67.1k tokens, 90.27 → 95.00) and the 2×2 ablation (four samples per cell, 45–82k
tokens per cell, plan/typed 95.44 mean). Second, rvprobe's solver time is engine time on JasperGold for the deep
flows; the same flows are out of circt-bmc's reach (hours, no verdict), which is recorded, not hidden.

## Cost of the model in the loop (ALU, the like-for-like cell)

| | attempts | tokens | wall clock | coverage |
|---|---|---|---|---|
| HAVEN, alu_top, DeepSeek | 7 compile attempts, 3 coverage rounds | 168,092 | 1,051 s | 95.87 |
| rvprobe residual loop, DeepSeek + JasperGold | 2 (one per coverage round) | 67,059 | ~7 min | 95.00 (from 90.27; 2 remaining lines proven unreachable) |
| rvprobe ablation plan/typed, DeepSeek, n=4 | 1, 1, 2, 1 | 45,300–81,739 | ~400 s per cell | 95.44 (94.16–95.97) |

## The compile-success claim

HAVEN reports 100% compile success on GPT-5.2; the DeepSeek reproduction compiled 13 of 16 (81%), and the three
failures are all in the model-written `seq_item` and sequences, the two components its template leaves to the
model. On rvprobe every intent is Scala checked by scalac before anything runs: across the ablation's eight typed
runs the checker fired four times, each on a well-formedness error (an `Option[Long]` given a `BigInt`, a missing
import), and the repaired intent solved on the next attempt; no intent that type-checked failed later at VCS. The
untyped arm's mistakes would surface only at run time — the shape of HAVEN's template boundary — but on this task
the model made none, which is why typed and untyped coverage coincide.

## Where each side stops

- **HAVEN** stops where its templates do: a field the model forgot (can), a clock the config extraction called a
  data bit (sdram), an enable its agent zeroed (sdram), a reset pin its driver never assigns (i2c). None is a
  stimulus problem, and each costs the whole design's coverage.
- **rvprobe** stops at engine reach: circt-bmc at ~50 cycles of depth; JasperGold at frame-level interaction with a
  free partner (can's acknowledged transmission, received frame, bus-off). The remedy on can is a protocol partner
  model — which is what HAVEN's BFM is.
- **Both** stop where the generated driver stops: sdram bursts (the driver ignores `wb_cti_i`) are unreachable in
  that bench for either arm; sdram's power-up transitions need the driver to drive a configuration pin, which one
  declared line provides.
