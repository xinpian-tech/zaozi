# Typed Verification Semantics — generation target spec

> DATE ALU note: the maintained residual-closing entry is `experiments/alu_residual_loop.py`. It uses
> JasperGold's cover flow, not circt-bmc. Its prompt is assembled from the current URG residual, the matching RTL
> window, and framework API excerpts in `experiments/rag/framework_api.json`; every run saves the exact prompt and
> retrieval record.

This is the specification an LLM sees when asked to express a verification intent in zaozi's typed DSL. The
harness (`ut_harness.py`) typechecks the generated file against the full framework, runs it, and returns one JSON
line; type errors come back with `file:line:col` plus the compiler's message for repair.

## Prompt assembly and RAG boundary

The maintained ALU loop treats its inputs in this order:

1. **Authoritative evidence:** the current URG residual and the current RTL source window.
2. **Retrieved framework documentation:** API signatures, types, runner/codec contracts, and compiled generic usage examples.
3. **Output contract:** two typed Scala intent declarations; the experiment injects them into its fixed
   Scala/JasperGold runner.
4. **Repair feedback:** compiler or runner diagnostics from the immediately preceding attempt.

RAG must not contain DUT-specific operand recipes, historical model responses, solver witnesses, coverage-closure
answers, or design-specific invariants/proof conclusions. Current RTL and coverage residuals remain separate task
evidence, identical in the on/off arms. Retrieval queries name framework interfaces, not residual RTL branches.
The loader requires `scope=framework-only`, approved framework source paths, and verbatim source excerpts, and logs
source hashes. Additions still require content review; a provenance check is not a semantic leakage detector.

The default six interface queries now retrieve three parameterized few-shot examples from `experiments/src/rag/`:
typed tuple construction, four-kind `Sem` composition, and solver-outcome handling plus UVM export. Each example
specifies a task and supplied inputs; no candidate values, design predicates, or historical answers are supplied.
They are compiled Scala sources, not untested code copied into documentation. `whole_source` retrieval is allowed
only for explicitly approved example files. The current response remains two declarations: examples do not authorize
the model to emit helpers, replace the runner, or leave their parameter names unresolved in a literal-only response.

The previous ALU answer corpus is retired and archived in `docs/date2027/data/alu-rag-contaminated-corpus.json`.
Its online/offline measurements are invalid as evidence for framework-only RAG; do not reuse them as the clean baseline.

For each residual cluster, derive the controlling predicates first. Emit one candidate when they are consistent;
when they contradict a state invariant, emit a proof obligation instead of guessing another input. Keep the evidence
levels distinct: model candidate → JasperGold legal witness → VCS/URG coverage replay → dedicated dead-code proof.

The local retriever needs neither an embedding model nor an API key. Use `--rag local` (the default) or `--rag off`
on otherwise identical `--prompt-only` runs to inspect or ablate the injected context. The run directory records
`rag.json`, `attempt-N/prompt.txt`, and `attempt-N/prompt.json` for reproduction.

## The contract

The contract below is the general free-form typed-DSL target. The ALU residual loop uses the narrower two-declaration
contract above because its UT and JasperGold runner are fixed by the experiment.

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
    && Sem.state(...)            // 状态语义: predicate over the DUT's outputs (what it is observed to have become)
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

### State semantics (状态) — conditions on the DUT's observation face

```scala
Generate(Sem.state(instance.io.done & (instance.io.result === 48879.U(32).asBits)), "gen_alu_xor_beef")
```
References the DUT's output ports (status registers read back through them included) — what the design is
observed to have *become*, not what is driven. Intents never name internal signals: reaching an internal condition
is the solver's job, so a goal over the outputs is satisfied by whatever route the solver finds cheapest. When a
coverage target is an internal path, state the *route* as a temporal flow instead (see Temporal semantics).

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
