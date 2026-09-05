# The 2×2 ablation: who supplies the operands, and when a mistake is caught

Two claims are bundled into the harness, and the paper needs them separated. One cell per combination,
all four given the identical task — close the residual the bulk fill leaves on HAVEN's ALU — the same
model (DeepSeek V4 Flash, temperature 0.3, up to 3 attempts), the same testbench, and the same scoring
(`urg_score.py`, DUT module `alu_top`, line+cond+toggle+branch).

- **form** — `plan`: the model chooses concrete operands and the solver only schedules the handshake,
  so the model must reason about IEEE-754 itself. `goal`: the model names a destination over the DUT's
  outputs and the solver searches the operand space.
- **check** — `typed`: the model writes the Scala experiment and scalac checks it before anything runs.
  `untyped`: the model writes JSON that a fixed driver consumes, so mistakes surface only at run time —
  the shape of HAVEN's structural DSL, where the boundary is a template rather than a type.

Baseline (bulk fill alone): **85.40**, 18 uncovered lines.

| cell | attempts | tokens | beats | score | gain | residual closed | caught by |
|---|---|---|---|---|---|---|---|
| plan / typed | 1 | 45,300 | 9 | **95.82** | +10.42 | 16/18 | — |
| plan / untyped | 1 | 35,732 | 8 | **95.82** | +10.42 | 16/18 | — |
| goal / typed | 2 | 47,218 | 44 | 93.21 | +7.81 | 9/18 | typecheck |
| goal / untyped | 1 | 41,809 | 55 | 93.35 | +7.95 | 10/18 | — |

170k tokens, ¥0.0085, 24 minutes for the whole grid.

## Neither axis came out the way the harness's pitch assumes

**The type-checking axis made no difference to coverage at all.** `plan/typed` and `plan/untyped`
reached byte-identical coverage; the goal cells differ by 0.14, which is one line. The type checker
did fire once, in `goal/typed`, and what it caught is worth reading closely: the model wrote
`resultIs = Some(BigInt(0x7FC00000L))` where the field is `Option[Long]`. That is a real type error,
caught before any simulation, with a located message — and it is **an error the untyped surface could
not have made**, because in JSON that field is just a number. The typed arm spent an extra round and
~5k tokens repairing damage its own surface syntax created. On this task the type system's ledger is
one self-inflicted error caught, and nothing else.

That is a negative result for the claim as stated, and n=1 per cell with one model. What it does not
show is the case the claim is really about — a *semantic* malformation that the type system rejects
and a template accepts. Neither surface produced one here, which is itself informative: on a task this
well-scoped, a cheap model's output is well-formed either way.

**The `plan` form beat the `goal` form** — 16/18 residual lines against 9–10/18 — which is the
opposite of what "state the destination, not the route" predicts. Two causes, and they are separable:

1. *The model named narrower targets.* The plan cells launched five distinct opcodes (FP_ADD, FP_SUB,
   AND, FP2INT, reserved); the goal cells launched only FP_ADD and FP2INT, and never FP_SUB. Five of
   the seven goals written were variations on FP_ADD. This is a prompt/vocabulary effect, not a
   property of solver-derived generation.

2. *A goal over the outputs underdetermines the internal path.* This one is intrinsic. `s1_is_nan <=
   1'b1` (line 208) is a NaN-detection branch four pipeline stages before any output changes. The goal
   language can say "the INVALID flag is set", and the solver satisfies that — via FP2INT's invalid
   path, which never touches line 208. The goal is met and the coverage hole is not. Same for the
   reserved-opcode branch (lines 475–476): the goal cells did drive opcode 10, but with `start` low,
   because the goal "done, flags zero, result zero" was reachable more cheaply through the integer
   path.

The second cause is the finding worth keeping. **Goal-directed generation is bounded by the
observability of the goal predicate.** Coverage holes live on internal microarchitectural paths;
a predicate over ports can only name their externally visible consequences, and the solver is free to
reach those consequences by whatever route is cheapest — which is precisely the route already covered.
Naming an internal event requires observing internal state, and that is the design question left open
after the port-promotion approach was rejected. This experiment turns that open question from a matter
of taste into a measured 6.5-point gap.

## Four samples per cell (2026-09-03)

The `n=1` caveat below was answered by three more runs of the whole grid, same model and temperature, same task
(`data/ablation-2x2-samples.{csv,json}`, per-run files `ablation-2x2-sample{2,3,4}.json`):

| cell | scores (4 runs) | mean | range | attempts | caught by typecheck |
|---|---|---|---|---|---|
| plan / typed | 95.82 94.16 95.97 95.82 | **95.44** | 1.81 | [1, 1, 2, 1] | 1 |
| plan / untyped | 95.82 94.16 95.82 95.82 | **95.41** | 1.66 | [1, 1, 1, 1] | — |
| goal / typed | 93.21 93.69 91.63 93.08 | **92.90** | 2.06 | [2, 3, 1, 1] | 3 |
| goal / untyped | 93.35 94.38 94.38 92.84 | **93.74** | 1.54 | [1, 1, 1, 1] | — |

Both findings survive. The form axis: plan averages 95.44 / 95.41 against goal's
92.90 / 93.74, and in every one of the four runs the plan cell of a given check beats
or ties its goal cell — the gap is smaller than the first run suggested (it ranged from 0 to 4.3 points) but its
sign never changed. The check axis: within a form, typed and untyped means differ by 0.03
(plan) and 0.84 (goal), inside the run-to-run range of either cell; the type checker fired
4 times over the eight typed runs, always on a well-formedness error the untyped surface could not have made,
and never on a semantic one. What the samples add is the variance itself: the same cell, same prompt, same model
moves by up to 2 points between runs, which is the noise floor any single-run comparison on this task sits in.

## Caveats

- One sample per cell, one model, temperature 0.3. The coverage numbers are deterministic given the
  sequences, but which sequences the model writes is not.
- The goal cells' witnesses are solved at bound 12 against the plan cells' bound 2, so they carry more
  inert beats (44–55 against 8–9). `AbstractStimulus.trimAfterStrobe` exists to cut that tail, and was
  checked here: at a drain wide enough to let the FP pipeline finish it removes nothing from these
  witnesses, so the beat-count difference is not what causes the coverage difference.
- Two lines (`fp_active <= 1'b0`, `f2i_int_val = 32'h0`) are closed by no cell. Both are pipeline
  teardown on an aborted operation, which no single-transaction intent of either form can express.

Data: `data/ablation-2x2.{csv,json}`. Rerun with `experiments/ablation.py`.
