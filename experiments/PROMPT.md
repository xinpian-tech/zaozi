# Typed Verification Semantics — generation target spec

This is the specification an LLM sees when asked to express a verification intent in zaozi's typed DSL. The
harness (`ut_harness.py`) typechecks the generated file against the full framework, runs it, and returns one JSON
line; type errors come back with `file:line:col` plus the compiler's message for repair.

## The contract

Produce ONE Scala 3 file. It must define `object Generated extends UTExperiment` whose `run` performs one
solve-and-save via `UTCli.generateReport`, plus the UT module(s) it solves. Skeleton:

```scala
import me.jiuyang.stdlib.*            // stock DUTs (AbsVal, Accum, TwoBeat, Queue, …)
import me.jiuyang.utlib.*             // UT, UTCli, UTExperiment, Txn
import me.jiuyang.zaozi.*             // the typed DSL
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*      // temporal: Sequence, ClockEvent (only if needed)
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

// … UT module definition(s) …

object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    UTCli.generateReport(<YourUT>, <ItsParameter>, bound = <cycles to unroll>, outDir)
```

## Expressing a constraint

A constraint C ("what the generated stimulus must satisfy") is stated as its negation: `Assert(!C)` in the module
body. The solver's counterexample IS a stimulus satisfying C. Rules that matter:

- The `Assert` carrying ¬C goes in the module **body**, never inside `layer("Verification"){}` — layers hold
  observation plumbing and are stripped from the formal model.
- A UT module `extends Generator[...] with UT[...]`, wraps its DUT via `<DUT>.instantiate(parameter)`, passes IO
  through, and re-exposes the DUT's probe inside the Verification layer (that part IS layered).
- `bound` must cover the cycles the constraint needs (a k-beat relation needs bound ≥ k+1).

### Value constraints (single beat)

```scala
// C = "A is odd" — asserted as ¬C:
Assert((!io.A.bit(0)).I, "gen_a_odd")
```
`.I` marks an immediate (combinational) assertion. Comparisons: `===`, `>`/`<` (via `.asSInt`/`.asUInt`),
bit access `.bit(i)` / `.bits(hi, lo)`, boolean `!`, `&`, `|`.

### Temporal constraints (patterns across cycles)

```scala
given ClockEvent = posedge(io.clock)
val beat = (io.A === three).S ### (io.A === five).S   // ### = SVA ##1 (next cycle); ## = same-cycle fusion
Assert(!beat, "gen_two_beat")                          // ¬C over the clocked sequence
```
`.S` lifts a boolean to a clocked sequence atom; combinators: `###` (1-cycle delay concat), `##(n)`, `*` (repeat),
`|->` (implication), `throughout`, `until`, `iff`.

### Cross-transaction constraints (relations between beats)

```scala
given ClockScope = ClockScope.posedge(io.clock)
given ResetScope = ResetScope.syncActiveHigh(io.reset)
val (hist, beats) = Txn.history(io.A, width, 2)        // hist(0) = 1 beat ago, hist(1) = 2 beats ago
val window   = beats === 2.U(2)                        // guard: the whole window is real history
val distinct = !(hist(1) === hist(0)) & !(hist(1) === io.A) & !(hist(0) === io.A)
val sum3     = (hist(1).asUInt + hist(0).asUInt + io.A.asUInt).asBits.bits(width - 1, 0)
Assert((!(window & distinct & (sum3 === 12.U(width).asBits))).I, "gen_three_distinct_sum12")
```

### Constraints through an external SystemVerilog IP

Wrap the IP as a `VerilogWrapper` (see `stdlib/src/ExtAccumUT.scala`), instantiate it in the UT, and state the
relation over its outputs. In a sequential UT also add `Assume((!io.reset.asBool).I, "rst_low")` so the model
tracks the post-reset run.

## Worked examples (all in-tree, all green)

| Constraint class | UT module | Test |
|---|---|---|
| value | `stdlib/src/AbsValOddUT.scala` | `AbsValFormalGenTest` |
| temporal | `stdlib/src/TwoBeatUT.scala` | `TwoBeatFormalGenTest` |
| cross-transaction | `stdlib/src/AccumUT.scala` | `AccumFormalGenTest` |
| external SV IP | `stdlib/src/ExtAccumUT.scala` | `ExtAccumFormalGenTest` |

## Result semantics

`{"phase":"solve","result":{"status":"generated","cycles":N,"trace":{...},"stimulusFile":...}}` — a witness; the
stimulus file replays on the Model B testbench. `"infeasible"` — no trace within `bound` satisfies C (the flow
worked; raise `bound` or weaken C). `"unknown"` — the solver could not decide; the `detail` field says why.
