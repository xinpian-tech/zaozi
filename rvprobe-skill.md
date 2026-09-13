---
name: rvprobe
description: Use when writing or repairing RVProbe LTL goals. Express verification intent on supplied IO using zaozi LTL; framework syntax only, never DUT answers.
---

# Express an IO intent with LTL

Core syntax only, never DUT scenarios or operands. Choose a small intent batch from
spec, IO and measured feedback; the framework owns later coverage rounds.
Use supplied file IDs/physical conditions and batch independent RTL reads.
Use supplied current_feedback and accepted_ltl directly when present: they are
complete observations from this run, not historical answers. Otherwise read
complete compact gaps with read_coverage({}); page size is automatic, and a
non-null next_offset means more rows remain. Its columnar groups are lossless,
not a priority list. Consider all coverage types rather than only the first page.
The IO table supplies directly bound port identifiers (no `io.` prefix); use its
explicit alias if a port name conflicts with Scala or API names. Do not redefine
port identifiers. Signals keep their Bool/Bits/UInt/SInt types; they are not all sequences.
The framework supplies all imports and the sampled clock/reset context. Return only
LTL expressions and local predicate/sequence helpers. Read additional LTL references
only for an expression API missing here; never inspect UT construction or runner ABI.

## Goal expressions

Use `Gen(expression, "unique_snake_case_label")` once per intent in one LTL fragment.
Gen accepts hardware Bool, Sequence or Property and emits native Cover;
the runner owns sequence sampling. No value/state wrappers.

`p`, `q`: IO Bool predicates; `bits`: IO Bits; `value`: BigInt;
`width`, `gap`, `lo`, `hi`, `cycles`: Int parameters, not benchmark constants.

| Intended relation | zaozi expression |
| --- | --- |
| Both predicates now | `p & q` |
| Either predicate now / negation | `p \| q` / `!p` |
| A Bits value equals a chosen value | `bits === value.B(width)` |
| Both events, one cycle apart | `p ### q` |
| Both events, exactly gap cycles apart | `p.##(gap)(q)` |
| Both events, between lo and hi cycles apart | `p.##(lo, Some(hi))(q)` |
| Value differs from the value cycles earlier | `!(past(bits, cycles) === bits)` |
| Difference after an observed starting event | `p.##(cycles)(!(past(bits, cycles) === bits))` |

`###`, `.##(q)` (zero delay), fixed and bounded `.##(...)(q)` accept Bool or Sequence
operands, lifting only Bool with the current clock. Chaining `p ### q ### !p`
works. Bits/numeric values and Property are not sequence operands.
`&`, `|`, `!` on Bool remain hardware Boolean operations; this is not a global
Bool-to-Sequence conversion. `.S` remains available and is required for other
sequence-specific APIs such as `p.S.*(n)`. Fixed delays must be nonnegative;
bounded delays require `0 <= lo <= hi`. Endpoint examples leave intermediate
cycles free: explicitly include necessary gap conditions.
`past(value, cycles)` preserves Bool/UInt/SInt/Bits type and width, requires
`cycles > 0` and ClockEvent, and has NO automatic history guard. The last example
establishes history by observing `p` first. Respect task-specific backend restrictions.

Request events themselves: implication can succeed with no antecedent occurrence.
Scenario conditions belong inside Gen, never added Assume/restrict/global constraints.
No extra Assert/Cover, DUT-internal references, replacement DUT or host operations.

Inside the supplied IO and clock context, compose local predicates directly.
For symbolic IO Bool predicates p and q (not DUT scenarios):

```scala
val first = p & q
val second = !p & q
val ordered = first ### second ### p
Gen(ordered, "symbolic_event_order")
```

The example occupies three consecutive sampled cycles; it leaves other inputs free.
Use the same local-val style for longer expressions. Do not copy p/q or the example
label as task goals. Express the chosen relation, not a manually solved witness:
JG searches input values and cycles. Do not enumerate candidate assignments or
simulate the DUT in prose before emitting LTL. Coverage feedback guides the next round;
one response need not exhaust all gaps or establish that every remaining gap is unreachable.
For many delayed steps, keep parentheses shallow: `val pair = p.##(gap)(q)`
then `val triple = pair.##(gap)(p)`. Named intermediate sequences avoid deeply
nested closing parentheses; they do not change the sampled temporal relation.

## Output contract

Return raw Scala LTL, without JSON, Markdown or prose. Local vals and pure helper
defs may compose expressions, followed by `Gen(expression, "label")` calls.
Labels must be literal, unique snake_case strings; no separate label list.
Return 1..64 goals, subject to the task batch limit, or STOP alone if no new goal remains.
STOP does not prove unreachability or coverage closure.

The framework constructs one fixed UT with imports, DUT binding, faithful IO wiring,
clock/reset scopes and runner. Do not emit or redefine any of those. Do not assign
IO or add assumptions. IO outputs may appear in goals but are produced by the DUT,
never driven by the model. No proof metadata or manually generated sequences.

## Type errors and repairs

Hardware signal parameters are `Referable[D]`, not the datatype `D` itself:
use `Referable[Bool]`, `Referable[Bits]`, `Referable[UInt]` or `Referable[SInt]`.
This accepts IO references, computed `Node[D]` and literal `Const[D]` values.
Predicate helper results are `Referable[Bool]` (or inferred), not `Bool`;
temporal helper results are `Sequence` or `Property` as appropriate.
Define helpers locally in the fragment; their compilation/clock context is already supplied.
For symbolic expressions only:

```scala
def same(x: Referable[Bits], y: Referable[Bits]): Referable[Bool] = x === y
def gated(p: Referable[Bool], q: Referable[Bool]): Referable[Bool] = p & q
```

Repair LTL syntax/types locally, preserving goals, operands and temporal conditions.
Fix syntax first: downstream type errors may cascade. Full diagnostics remain readable.

- Hardware Bool uses `&`, `|`, `!`, not Scala `&&` / `||`; it has no `.asUInt`.
  Parenthesize comparisons before combining predicates.
- Match equality operand types: Bits with `value.B(width)`, or convert Bits
  via `.asUInt` and compare with `value.U(width)`. Raw Bits is not a Gen predicate.
- For numeric text use `BigInt(digits, radix)` before `.U(width)` / `.B(width)`;
  String has no `.U`. Avoid overflowing Scala Int literals.
- Call fixed delay as `p.##(gap)(q)`, not the ambiguous infix `p ##(gap)(q)`.
- Ordinary Scala identifiers need no backticks. When an identifier requires them,
  quote only the complete identifier, not an expression or a following operator.

Only validated, design-independent API lessons belong in this skill. Never import
historical UTs, witnesses, benchmark operands or design-specific invariants from repair logs.
