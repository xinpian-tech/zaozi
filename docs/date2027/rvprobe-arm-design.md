# Running the HAVEN experiment with rvprobe (the A3 arm)

How to put our typed-DSL framework on the same benchmark HAVEN was just measured on, and what has to
be built first. Companion to `haven-deepseek-reproduction.md` (the baseline) and
`experiment-plan.md` (the four-arm design).

## The framing problem, stated honestly

HAVEN and rvprobe do not produce the same kind of artifact:

| | HAVEN | rvprobe |
|---|---|---|
| Output | a complete UVM testbench (driver/monitor/scoreboard/sequences) | one k-cycle witness per solved intent |
| Volume | 10k+ transactions per run | a handful of cycles |
| Selection | constrained-random + Bayesian tuning of the distribution | a solver constructing exactly the trace the intent demands |

A head-to-head "who gets higher total coverage" race is therefore rigged against us and misses the
point: random volume is *good* at the bulk of a coverage model and *bad* at its tail. Our claim has
always been about the tail — the post-saturation gap.

**So the experiment is not a race, it is a residual-closing study**, and the run we just finished
gives us the residuals for free:

> Take HAVEN's testbench as the baseline stimulus. Measure what it leaves uncovered. Point rvprobe at
> those specific items. Report how many the solver closes that HAVEN's own coverage-improvement loop
> could not.

This is methodologically airtight because it holds everything else fixed — same 16 designs, same VCS,
same URG metrics, same machine — and it targets exactly where the baseline is weakest. The residuals
are large and already measured: sdram 89% uncovered, i2c 37%, ue_gpio 16%, and can/ethmac/ue_spi
where HAVEN produced no compiling testbench at all (100% residual).

## The pipeline

```
URG residual report  →  LLM writes a typed intent targeting one uncovered item
                        (state: FSM reaches S; value: opcode ∈ reserved; relation: …)
                     →  scalac typechecks it            ← the early boundary we claim helps
                     →  circt-bmc solves ¬C             ← the solver constructs the trace
                     →  witness → AbstractStimulus → replay under VCS -cm
                     →  URG again: did the item close?
```

Each stage already exists except coverage. Note what is different from HAVEN at stage 3: an
ill-formed intent fails at compile time with a located message that feeds the repair loop, and a
well-typed intent cannot produce an incoherent stimulus because the solver, not the model, builds it.
That is the mechanism the weak-model result is supposed to expose.

## What must be built (four items, in order)

1. **The coverage leg** — the one real gap (item 5 of the infra list, never built). Cheaper than it
   looks: `Driver.topString` emits plain SystemVerilog (`always #5 clock`, a DPI-C tick callback), so
   the Model B testbench runs under VCS unmodified. Needed: a `vcs -sverilog -cm
   line+branch+tgl+fsm+cond` build path beside the Verilator one, and a URG report parser. Both the
   FHS wrapper (`snps-fhs-env` + `snps-shell`) and the URG output format are already understood from
   today's HAVEN run — reuse them so *both arms are measured by the identical toolchain*.
2. **Multi-drive stimulus** — HAVEN's designs have wide input sets. Packing everything into one
   ≤64-bit word worked for ALU and SPI but does not scale (and the DPI return is width-limited).
   `AbstractStimulus` already carries every drive port per beat; what is missing is a codec and a
   tick callback that fill several ports — a columnar stimulus file plus a multi-output DPI callback.
3. **Witness volume** — one witness is k cycles; closing many items needs many witnesses concatenated
   into one replay, plus blocking constraints (`assume` the previous solution away) to get *distinct*
   traces from one intent. Both are small additions on top of the existing solve loop.
4. **UT wrappers at scale** — two designs are wrapped by hand; fourteen are not. This is the
   bottleneck, and it is also measurable: have the LLM generate the wrappers and report the
   type-check pass rate, which is itself a result about the typed surface.

## Scaling risk, stated up front

Bounded model checking over ethmac or sdram will not behave like it does over the ALU. Mitigations,
in order of preference: keep bounds small and target items whose cone of influence is local; solve
against the module containing the hole rather than the full top; accept and *report* where BMC does
not scale. "Directed generation closes tail items on designs up to size N, and does not scale past
it" is a legitimate and useful finding — much better than quietly excluding the hard designs.

## Recommended first cut

Do not start with all 16. Take three designs that stress different points:

- **i2c** — HAVEN reached 63.2%, a large residual on a design of moderate size. The likely best case.
- **sdram** — HAVEN reached 11.0%. If rvprobe closes items here the contrast is dramatic; if BMC
  does not scale, we learn the boundary early.
- **can** — HAVEN produced *no compiling testbench* in five repair attempts. A typed intent that
  compiles and solves at all is already a qualitative win, and it directly exercises the
  "errors surface at scalac, not at VCS" claim.

Build item 1 (coverage), wrap those three by hand, and run the residual-closing loop. That is enough
to know whether the full sweep is worth it, and it produces the paper's core figure either way.

## The second axis: strong vs weak model

Everything above should be run with both a frontier model and DeepSeek V4 Flash. HAVEN's weak-model
profile is now known — parity on simple designs, collapse on complex ones (i2c −26.8, sdram −59.0,
three designs failing outright). The prediction under test: **the typed DSL narrows that gap**,
because the failure HAVEN suffers (semantic incoherence discovered only at VCS compile time, then
five exhausted repair attempts) is converted into a located type error the model can act on. If the
gap does not narrow, that is a real negative result and worth knowing before the paper commits to the
claim.
