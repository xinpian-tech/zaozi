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

## Expressing a constraint: the four typed semantic kinds

Verification intent is written as a typed `Sem.Intent` — four kinds of meaning, composed with `&&`, lowered by
`Generate(intent, label)` in the module **body** (never inside `layer("Verification"){}` — layers are stripped from
the formal model). The solver's counterexample IS a stimulus satisfying the whole intent. Composition uses
fire-cycle semantics: value/relation/state conjuncts hold at the cycle the scenario fires; each temporal sequence
matches forward from that cycle.

```scala
Generate(
  Sem.value(...)                 // 值语义: predicate over the driven object's fields
    && Sem.relation(w) { w => ... } // 关系语义: relates different beats, from a declared window
    && Sem.state(...)            // 状态语义: predicate over design state (DUT outputs / status)
    && Sem.temporal(...),        // 时序语义: clocked SVA sequence (needs `given ClockEvent`)
  "label"
)
```

### Value semantics (值) — one object's fields

```scala
Generate(Sem.value(io.A.bit(0)), "gen_a_odd")   // C = "A is odd"
```
Comparisons: `===`, `>`/`<` (via `.asSInt`/`.asUInt`), bit access `.bit(i)` / `.bits(hi, lo)`, boolean `!`, `&`, `|`.

### Relation semantics (关系) — between different beats

```scala
given ClockScope = ClockScope.posedge(io.clock)
given ResetScope = ResetScope.syncActiveHigh(io.reset)
val w = Txn.window(io.A, width, 2)              // w.past(1) = previous beat, w.past(2) = two back
Generate(
  Sem.relation(w) { w =>
    val distinct = !(w.past(2) === w.past(1)) & !(w.past(2) === io.A) & !(w.past(1) === io.A)
    val sum3     = (w.past(2).asUInt + w.past(1).asUInt + io.A.asUInt).asBits.bits(width - 1, 0)
    distinct & (sum3 === 12.U(width).asBits)
  },
  "gen_three_distinct_sum12"
)
```
The window's reality guard is conjoined automatically — a relation can never fire on fake history, and the
declared depth is a type: `w.past(3)` on a depth-2 window fails to COMPILE.

### Temporal semantics (时序) — ordering between events

```scala
given ClockEvent = posedge(io.clock)
Generate(Sem.temporal((io.A === three).S ### (io.A === five).S), "gen_two_beat")
```
`.S` lifts a boolean to a clocked sequence atom (a `ClockEvent` must be in scope — an unclocked temporal constraint
does not compile); combinators: `###` (SVA ##1), `##(n)`, `*` (repeat), `|->`, `throughout`, `until`, `iff`.

### State semantics (状态) — conditions on design state

```scala
Generate(Sem.state(instance.io.done & (instance.io.result === 48879.U(32).asBits)), "gen_alu_xor_beef")
```
References the DUT's outputs, probes, or status registers — what the design has *become*, not what is driven.

### Constraints through an external SystemVerilog IP

Wrap the IP as a `VerilogWrapper` (see `stdlib/src/ExtAccumUT.scala`), instantiate it in the UT, and state
value/state semantics over its ports. In a sequential UT add `Txn.assumeResetLow(io.reset)` and feed
`Txn.firstCycle()` into the IP's reset so it initializes itself. `bound` must cover the cycles the intent needs
(a k-beat relation needs bound ≥ k+1). Probe re-export is one line per signal:
`Probes.expose(probe.SUM, Bits(width), instance.io.sum)` inside `layer("Verification")`.

## Worked examples (all in-tree, all green)

| Semantic kinds | UT module | Test |
|---|---|---|
| value | `stdlib/src/AbsValOddUT.scala` | `AbsValFormalGenTest` |
| temporal | `stdlib/src/TwoBeatUT.scala` | `TwoBeatFormalGenTest` |
| relation | `stdlib/src/AccumUT.scala` | `AccumFormalGenTest` |
| **all four composed** | `stdlib/src/SemAccumUT.scala` | `SemAccumFormalGenTest` |
| state, through an external SV IP | `stdlib/src/ExtAccumUT.scala`, `HavenAluUT.scala` | `ExtAccumFormalGenTest`, `HavenAluFormalGenTest` |
| value ∧ state on a bus protocol | `stdlib/src/HavenSpiUT.scala` | `HavenSpiFormalGenTest` |

## Result semantics

`{"phase":"solve","result":{"status":"generated","cycles":N,"trace":{...},"stimulusFile":...}}` — a witness; the
stimulus file replays on the Model B testbench. `"infeasible"` — no trace within `bound` satisfies C (the flow
worked; raise `bound` or weaken C). `"unknown"` — the solver could not decide; the `detail` field says why.
