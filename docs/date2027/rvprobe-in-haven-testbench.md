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

| | transactions | score | line | cond | toggle | branch |
|---|---|---|---|---|---|---|
| rvprobe (10 directed intents) | **10** | 44.51 | 67.72 | 52.83 | 8.01 | 55.38 |
| HAVEN (15 sequences + 3 BO iterations) | **10,110** | 95.87 | 97.88 | 92.92 | 97.28 | 95.38 |

**The plumbing works**: solved stimulus runs inside a foreign testbench with only the sequence layer
replaced, measured by an identical toolchain.

**Read the numbers honestly.** HAVEN wins on absolute coverage by a wide margin, and this is not a
fair coverage comparison — 10 transactions against 10,110. What it does establish is efficiency per
transaction: 4.45 coverage points per transaction versus 0.0095, roughly 470×. Ten solver-chosen
transactions reach 44.5% of a design that a thousand-fold more random ones take to 95.9%.

**Where the gap is concentrated is the informative part.** Toggle coverage is 8.01% against 97.28% —
by far the widest margin — and that is exactly right: toggle coverage rewards bit-level *volume and
diversity*, which random stimulus produces for free and ten directed transactions cannot. Line
(67.7 vs 97.9) and branch (55.4 vs 95.4) sit much closer, because those are structural targets a
solver can aim at. The profile says plainly: directed generation is not a volume strategy, and any
honest framing has to pair it with volume rather than pretend to replace it.

## What this settles for the experiment design

- The comparable object really is stimulus; we do **not** need to generate UVM testbenches to be
  measured against HAVEN, which removes multi-drive codecs and per-design UT wrappers from the
  critical path (build items 2 and 3 of the arm design).
- Both arms can run inside *the same* infrastructure, so a coverage difference is attributable to the
  stimulus rather than to a hundred incidental testbench differences.
- The residual-closing study is the right primary experiment: rvprobe should be pointed at what
  volume leaves behind, and the toggle result is a concrete warning against framing it as a
  total-coverage race.

Artifacts: `utlib/src/UvmSequence.scala` (the codec), `stdlib/src/HavenAluUT.scala`
(`HavenAluOpUT`), `stdlib/tests/src/HavenAluUvmSeqTest.scala` (generates the sequence),
scratchpad `rvprobe-in-haven-tb/` (the spliced testbench and its URG report).
