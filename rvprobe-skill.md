---
name: rvprobe
description: Write or repair design-independent RVProbe LTL goals over supplied DUT IO.
---

# RVProbe LTL authoring

Return only a small batch of symbolic LTL goals over the supplied IO. The fixed
framework owns the UT, DUT binding, clocks, reset, solver, sampling and replay.
Never reproduce those pieces, a transaction driver, a concrete waveform, or a
benchmark answer.

Start with the authoritative DUT specification, IO table and current coverage
evidence. If they already determine a finite intent, emit LTL immediately in that same response;
do not read RTL merely to confirm a specification fact. Read RTL only when a
chosen output check, port mapping or timing relation needs one missing
implementation fact. Batch independent reads and reuse supplied
`previously_read_rtl`, `current_feedback` and `accepted_ltl`. Coverage rows are
current-run observations, not historical answers.

The IO table gives directly bound identifiers with no `io.` prefix. Use its
explicit alias for a reserved port name and do not redefine identifiers. Ports
retain their declared Bool/Bits/UInt/SInt types. `clock` and `reset` are framework
scope handles, not hardware
`Bool` predicates; never place them in an expression or pass them to `Gen`.

## Goals and temporal operators

Use `Gen(expression, "unique_snake_case_label")` once per intent. `Gen` accepts a
hardware Bool, Sequence or Property and emits the native Cover used for witness
generation. There are no value/state wrappers.

Here `p` and `q` are symbolic IO Bool predicates, `bits` is a symbolic numeric IO,
and all delay/value names are parameters rather than benchmark constants:

| Intent | Expression |
| --- | --- |
| predicates now | `p & q`, `p \| q`, `!p` |
| typed constant equality | `Ltl.is(bits, value)` |
| all zero / all one | `Ltl.isZero(bits)`, `Ltl.isOnes(bits)` |
| adjacent events | `p ### q` |
| exact delay | `p.##(gap)(q)` |
| bounded delay | `p.##(lo, Some(hi))(q)` |
| changed from earlier value | `!(past(bits, cycles) === bits)` |
| establish history then compare | `p.##(cycles)(!(past(bits, cycles) === bits))` |

For every curried delay, use the receiver dot (`.##`):
`p.##(lo, Some(hi))(q)`, never `p ##(lo, Some(hi))(q)`. The input boundary
canonicalizes only this unambiguous whitespace typo and records the edit; it does
not repair operands, bounds or intent. Returning canonical syntax avoids needless
repair work.

`###`, `.##(q)`, `.##(n)(q)` and `.##(lo, Some(hi))(q)` accept Bool or Sequence
operands and lift only Bool using the current clock. Fixed delays are nonnegative;
bounded delays require `0 <= lo <= hi`. Chaining `p ### q ### !p` creates three
consecutive sampled events. Intermediate cycles in a delayed relation remain free
unless the goal constrains them. `.S` is needed only for other Sequence-only APIs,
such as `p.S.*(n)`.

`&`, `|` and `!` on Bool are hardware operations, not Scala `&&`/`||` and not a
global Bool-to-Sequence conversion. Do not write `predicate & sequence` or
`predicate | sequence`: fold the predicate into the intended Boolean cycle, or
lift it explicitly (`predicate.S & sequence` for the common start;
`predicate.throughout(sequence)` when it must hold for the entire match).
`past(value, cycles)` preserves Bool/UInt/SInt/Bits type and width, requires
`cycles > 0` plus the supplied ClockEvent, and adds no history-valid guard.
Establish enough earlier activity in the same goal before checking a past value.

An implication may succeed without its antecedent occurring. Put the request and
its intended response/history inside the Gen scenario. Never add Assume, restrict,
global constraints or unrelated input-only replacements for a failed output goal.
JG searches concrete inputs and cycles; do not enumerate candidate assignments.

For a symbolic event order:

```scala
val first = p & q
val second = !p & q
val ordered = first ### second ### p
Gen(ordered, "symbolic_event_order")
```

Use local vals to keep long chains shallow. Local vals may hold predicates or
sequences; they do not add state or cycles.

## Backend boundary

The zaozi API supports properties beyond the JasperGold Cover backend. In this
generation path, unbounded `eventually(...)` becomes `s_eventually` and may be
rejected as liveness cover. For a genuinely finite scenario, express actual
ordered events with a bound justified by the supplied spec/configuration or RTL.
Never invent an arbitrary timeout merely to make a property compile. On capability
feedback, preserve every label, handshake, history and output check; do not return
STOP, remove goals or add assumptions.

Each Gen is solved independently. Other goals are not assumptions. The runner may
sample several distinct sequences for one intent, so do not duplicate a goal for
sampling. Compilation or a formal witness alone is not acceptance; the framework
performs fixed IO checks and native replay.

When `accepted_ltl.native_replay_outcomes` is supplied, `native-validated` goals
have accepted witnesses, `partial` goals have only the reported accepted subset,
and `unresolved` goals have none. Do not repeat an unresolved expression
unchanged. If pursuing the same intent, add its missing legal initialization,
handshake or history while preserving the output check; otherwise target a
different measured residual. A formal hit rejected by four-state replay is not
coverage progress.

If the solver reports `jg_goal_infeasible`, `jg_goal_unknown` or
`jg_goal_compile_timeout`, repair only the named goal expressions. Keep every
already-generated goal expression and every label unchanged. An output-dependent
goal normally needs its own finite input setup, protocol handshake and any
required history before the output observation; another Gen cannot provide that
state. Correct a missing or contradictory setup and temporal order without
replacing the intent with input-only activity, deleting an output check, adding
an assumption, or inventing a timeout. `infeasible` describes the expression
under the fixed DUT/reset/environment; it does not prove that the intended DUT
behavior is impossible. For `unknown` or compile timeout, remove unnecessary
temporal complexity while preserving the same finite setup and observation.

## Output contract

Return raw Scala LTL, preferably without Markdown. One complete whole-response
`scala` fence is accepted, but no prose, JSON, imports, modules or multiple blocks.
Allowed content is local `val`s, calls to supplied `Ltl` helpers and `Gen` calls.
Do not define functions or lambdas. Labels are literal, unique snake_case strings.
Return 1..64 goals subject to the task batch limit, or `STOP` alone only when no
useful new target remains. STOP is not a proof of unreachability or closure.

Use Gen only: no Assume, Assert, Cover, DUT internals, IO assignments, replacement
DUT, host operations or proof metadata. Outputs may be observed but never driven.
Before returning, mechanically check every delayed relation for `.##`, every
predicate for hardware `&`/`|`, and confirm neither `clock` nor `reset` occurs in a
goal.

## Supplied helpers

Call, never implement, `Ltl.is(signal, value: BigInt)`, `Ltl.isZero(signal)` and
`Ltl.isOnes(signal)`. They accept Bits/UInt/SInt references or computed nodes,
infer the signal type/width and return a hardware Bool. `is` rejects overflow;
Bits/UInt constants are unsigned and SInt constants must fit the signed range.
`isOnes` means every bit is one (SInt: -1). Helpers add no timing, history,
assumptions or registers. Bool signals use `p` and `!p` directly.

```scala
val zero = Ltl.isZero(bits)
val ones = Ltl.isOnes(bits)
Gen(zero ### ones, "symbolic_bit_pattern_order")
```

For a nontrivial wide constant use `BigInt("89abcdef", 16)`, not
`BigInt(0x89abcdef)`: Scala may overflow the inner Int/Long before BigInt sees it.
Use `Ltl.isOnes(signal)` for an all-one pattern. For signal-to-signal equality use
`===`; for Bits literal equality use `value.B(width)` or the typed helper.

## Local repair checklist

Repair only syntax, types or API use in the previous fragment. Preserve labels,
operands, temporal conditions and intended checks; do not replan coverage.

- Replace Scala `&&`/`||` with hardware `&`/`|` and parenthesize comparisons.
- A hardware Bool combined with an existing Sequence by `&`/`|` needs an
  explicit `.S`, or must be folded into the intended Boolean cycle. Use
  `throughout` only when the predicate must hold over the whole sequence.
- A raw Bits value is not a Gen predicate. Match Bits with `.B(width)`, or use
  `Ltl.is`; do not cast merely to silence a diagnostic.
- Write numeric text as `BigInt(digits, radix)` before `.U(width)`/`.B(width)`.
  A `Referable[Bits]`, `Referable[UInt]`, `Referable[SInt]` or
  `Referable[Bool]` is an IO value reference, not a datatype object. String has no
  `.U`, and an already-overflowed Int cannot be recovered.
- Write every curried delay as `p.##(gap)(q)` or
  `p.##(lo, Some(hi))(q)` with the receiver dot.
- Quote only a complete identifier when backticks are required; never quote an
  expression or following operator.

Only these design-independent API rules may be reused. DUT-specific operands,
addresses, witnesses and repaired benchmark invariants must remain private run
artifacts and never enter this skill.
