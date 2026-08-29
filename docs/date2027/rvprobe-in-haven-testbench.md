# rvprobe stimulus inside HAVEN's testbench (2026-08-29)

A small end-to-end experiment testing the claim that the two systems' comparable object is *stimulus*,
because a template-generated UVM testbench is fixed apart from its sequences.

## What was measured first

Across the 16-design run, with module names normalized:

| Component | Lines | Differing lines across designs |
|---|---|---|
| `wb_agent` | 27 | **0** |
| `wb_driver` | 94 | **0** |
| `wb_monitor` | 41 | **0** |
| `scoreboard` | 36 | **0** (identical even across *protocols*) |

Stimulus share of the emitted testbench: uart 80%, i2c 78%, simple_spi 70%, ALU 41%. Infrastructure is
roughly constant (~1100–1170 lines) regardless of design. Only four things actually vary: the
interface port list, the `seq_item` fields, the sequences, and how many agents the env builds.

So HAVEN's "generates a complete UVM testbench" is largely template instantiation, and its reported
100% compile success is substantially a property of those templates. (Our reproduction was 13/16, and
the failures were concentrated exactly where the model must assemble `seq_item`s and sequences
itself.)

## The experiment

Take the HAVEN ALU testbench, delete its `sequence_*.sv`, and drop in a sequence generated from
rvprobe witnesses — everything else (interface, seq_item, driver, monitor, agent, env, scoreboard,
top) reused **verbatim**.

- Ten intents, one per opcode: `Sem.value((io.op === k) & io.start)`, each solved through the
  imported RTL by circt-bmc.
- `UvmSequence` (a new `StimulusCodec`) renders the concatenated witnesses as concrete transactions —
  `txn.a = 32'h…; txn.op = 4'h…;` — with no `randomize()` anywhere.
- Compiled and run under the same VCS, coverage collected by the same URG.

## Result

An intent is a *constraint*, and a witness is only one of its solutions — so the same ten intents can
be replayed exactly, or expanded to volume by pinning the fields the intent constrains (`op`,
`start`) and randomizing the free ones (`a`, `b`). Both fills, same testbench, same VCS, same URG:

| | transactions | solver calls | score | line | cond | toggle | branch |
|---|---|---|---|---|---|---|---|
| rvprobe, witness replay | 10 | 10 | 44.51 | 67.72 | 52.83 | 8.01 | 55.38 |
| rvprobe, volume fill | 10,000 | 10 | 79.41 | 89.42 | 77.36 | **94.85** | 78.46 |
| + 6 hand-written residual intents | 10,006 | 16 | 90.40 | 93.12 | 86.32 | 96.02 | 86.15 |
| **+ 5 LLM-written residual intents** | **10,011** | **21** | **94.78** | **98.94** | 89.62 | 96.72 | 93.85 |
| HAVEN (4 initial sequences + 2 improvement rounds) | 10,110 | — | 95.87 | 97.88 | 92.92 | 97.28 | 95.38 |

**The plumbing works**: solved stimulus runs inside a foreign testbench with only the sequence layer
replaced, measured by an identical toolchain.

**Volume is not a limitation of the approach.** The first row's 44.5% was an artifact of replaying one
witness per intent, not a property of directed generation. Because the intent is a constraint, the
solver only has to supply the *structure* — ten BMC calls, once — while the simulator fills the free
data fields, and the transaction count becomes free. Toggle coverage moves 8.01 → **94.85** under
exactly this change, confirming the earlier diagnosis that the toggle gap was a volume artifact.

**Closing the residual works, and it is cheap.** Running URG with `-metric` exposes the uncovered
items, and for this design they were unambiguous: 20 uncovered lines, essentially all FP corner cases
— `s1_is_nan <= 1'b1`, `fp_result <= {s3_inf_sign, 8'hFF, 23'h0}` (infinity), `fp_flags[FLAG_ZERO]`,
`fp_flags[FLAG_OVERFLOW]`, denormal handling. Six intents naming those operand patterns (NaN, ∞+∞,
∞−∞, x−x, max+max, denormal±) added **six transactions** and moved the score **79.41 → 90.40**.
That is 1.8 coverage points per transaction, against 0.0035 for the bulk fill — the tail-closing
property the approach claims, measured rather than asserted.

**The remaining gap is 5.5 points, and the residual says what it is.** Thirteen uncovered lines remain, and they are a
tighter cluster than before: FP rounding/normalization (`s4_new_exp`, `s4_final_frac`,
`s4_round_up`), underflow (`fp_flags[FLAG_UNDERFLOW]`), and pipeline teardown (`fp_active <= 1'b0`).
These need operand pairs whose *arithmetic result* lands in a rounding or underflow case — a harder
intent to state than "a is NaN", and exactly the kind where naming the destination and letting the
solver find the operands should pay off.

## The loop closed by the LLM

The step the paper's claim rests on: DeepSeek V4 Flash was given the 13 remaining uncovered lines with
their RTL context, the opcode table, and the intent file's shape, and asked to write the intents
itself. `ut_harness.py` typechecked its output and would have fed any diagnostics back.

It compiled on the **first attempt** — no repair round — for 30,767 tokens (≈ ¥0.0015), and the five
intents it chose show it reasoning about the arithmetic rather than pattern-matching:

| intent | op | a | b | what it targets |
|---|---|---|---|---|
| `rounding_carry` | FP_ADD | `0x3f800000` (1.0) | `0x3f7fffff` (1−ε) | a sum whose mantissa needs rounding with carry |
| `underflow_tiny` | FP_SUB | `0x00800001` | `0x00800000` | smallest normals subtracted → underflow |
| `fp2int_nan` | FP2INT | `0x7fc00000` (NaN) | — | the invalid-conversion path |
| `fp2int_tiny` | FP2INT | `0x00800000` | — | a magnitude that truncates to zero |
| `reserved_opcode` | `0xA` | — | — | the reserved-opcode branch |

Choosing operands whose *arithmetic result* lands in a rounding or underflow case is exactly the task
that defeats route-planning: the model states the destination and the solver supplies the trace.

**Result: 90.40 → 94.78 from five transactions**, and uncovered lines fell from 13 to **2**. Line
coverage reached **98.94 against HAVEN's 97.88** — on the metric most directly tied to "was this code
exercised", directed generation now leads. Total: 94.78 vs 95.87, a 1.1-point gap, with 21 solver
calls and ~31k tokens for the whole closing loop.

The last two lines are `fp_active <= 1'b0` (pipeline teardown on an aborted operation) and
`f2i_int_val = 32'h0`. Both are plausibly reachable only via sequences the single-transaction intent
shape cannot express — a temporal intent, which the DSL has and this experiment did not use.

## What this settles for the experiment design

- The comparable object really is stimulus; we do **not** need to generate UVM testbenches to be
  measured against HAVEN, which removes multi-drive codecs and per-design UT wrappers from the
  critical path (build items 2 and 3 of the arm design).
- Both arms can run inside *the same* infrastructure, so a coverage difference is attributable to the
  stimulus rather than to a hundred incidental testbench differences.
- The residual-closing study is the right primary experiment: with volume no longer the differentiator,
  what separates the arms is which scenarios the intents name — so the coverage-feedback loop (read the
  URG residual, write an intent for it) is the mechanism under test, and 79.41 → 95.87 is the distance
  it has to cover.
- Solver cost scales with *intents*, not transactions: ten BMC calls produced ten thousand
  transactions. That decoupling is what makes a directed approach affordable at simulation volume.

Artifacts: `utlib/src/UvmSequence.scala` (the codec), `stdlib/src/HavenAluUT.scala`
(`HavenAluOpUT`), `stdlib/tests/src/HavenAluUvmSeqTest.scala` (generates the sequence),
scratchpad `rvprobe-in-haven-tb/` (the spliced testbench and its URG report).

## Footnote: what HAVEN's improvement loop actually did

Worth stating precisely, because "15 sequences + 3 BO iterations" overstates it. The ALU run's
progression:

| round | coverage | sequences |
|---|---|---|
| 0 (initial simulation) | 87.16 | 4 |
| 1 | **95.86** | 9 |
| 2 | 95.39 | 12 |

Four sequences came from the spec; the loop added five (+8.7 points) and then three more that made it
*worse* (−0.5), with the best snapshot preserved. It saturated after one productive round and
regressed on the next, each round costing a full simulation plus LLM and Bayesian-optimization work.

Our five LLM-written intents moved 90.40 → 94.78 monotonically for five transactions. The contrast is
worth making: tuning a *distribution* runs out of room once the reachable mass is covered, while
naming a *specific* uncovered item does not — a distribution has no way to express "the sum needs a
rounding carry".
