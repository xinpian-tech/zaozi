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
| **rvprobe, volume fill** | **10,000** | **10** | **79.41** | 89.42 | 77.36 | **94.85** | 78.46 |
| HAVEN (15 spec-derived sequences + 3 BO iterations) | 10,110 | — | 95.87 | 97.88 | 92.92 | 97.28 | 95.38 |

**The plumbing works**: solved stimulus runs inside a foreign testbench with only the sequence layer
replaced, measured by an identical toolchain.

**Volume is not a limitation of the approach.** The first row's 44.5% was an artifact of replaying one
witness per intent, not a property of directed generation. Because the intent is a constraint, the
solver only has to supply the *structure* — ten BMC calls, once — while the simulator fills the free
data fields, and the transaction count becomes free. Toggle coverage moves 8.01 → **94.85** under
exactly this change, confirming the earlier diagnosis that the toggle gap was a volume artifact.

**The remaining 16 points are about intent quality, not mechanism.** At matched volume (10,000 vs
10,110) HAVEN still leads 95.87 to 79.41 — but our ten intents were mechanically trivial
(`op == k ∧ start`), with no scenario diversity, no FP corner values, and no coverage feedback,
whereas HAVEN's fifteen sequences were derived from the datasheet and then tuned by three
Bayesian-optimization iterations against measured gaps. That is precisely the gap the residual-closing
loop is supposed to close, and it is now a measurable target rather than an assertion.

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
