# Running the HAVEN experiment with rvprobe (the A3 arm)

How to put our typed-DSL framework on the same benchmark HAVEN was just measured on, and what has to
be built first. Companion to `haven-deepseek-reproduction.md` (the baseline) and
`experiment-plan.md` (the four-arm design).

## "Isn't this just HAVEN plus a type checker?"

This is the reviewer's first question and it deserves a precise answer, because the easy answer is
wrong in both directions.

**Where the objection is right:** rvprobe is *not* restricted to one witness per intent. A weak
constraint solved repeatedly with blocking constraints yields a stream of traces, and the `ut`
lineage already had a bulk path (`e6b4d2f`, rejection sampling against an SVA assumption) that the
`date` branch simply hasn't rebuilt. So bulk stimulus is a missing feature, not a missing capability
— which means a head-to-head total-coverage comparison **is** legitimate, and we should run it.
Declining the race would read as evasion, and would leave the reader assuming we lose it.

**Where the objection is wrong:** type checking is the visible surface, not the engine. Two
differences are capability differences, not checking differences:

1. **Declarative vs procedural — verified against the generated artifacts, not assumed.** HAVEN's DSL
   is procedural *at the sequence level* and declarative *at the value level*. From today's run
   (`artifact/.../ir/phase4b_dsl_sequences.json`):

   ```
   i2c   write_single_byte_seq : register_write(slave_addr) → register_write(START=8'h60)
                               → poll(sr[4]==0) → register_write(data) → register_write → poll
   spi   transmit_byte_seq     : register_write(SPDR) → poll(status[7]==1)
                               → register_read(SPDR) → register_write(clear SPIF)
   alu   fp_add_operation      : randomize_send { a==32'h3f800000; b==...; op==4'h0; start==1 }
   ```

   The protocol designs get a genuine route — an ordered program with reactive `poll` waits — that
   the **LLM had to author from the datasheet**. The ALU degenerates to a single constrained
   `randomize_send`, i.e. value constraints, which is what our value semantics expresses directly.

   So the LLM supplies the *route*, and it gets that route by transcribing the spec. The consequence
   is the real claim: **HAVEN is strong exactly where the scenario is documented, and has nothing to
   copy from where it is not** — an unusual interleaving, an error path, a corner state no datasheet
   describes. rvprobe asks the model only for the destination and lets BMC find the route, documented
   or not. That is the tail, and it is where i2c (63.2%) and sdram (11.0%) left their residual.

   *Honest caveat on our side:* `poll` lets HAVEN wait an unknown number of cycles; a bounded witness
   cannot. Protocols with long waits need k ≥ that latency, which feeds directly into the scaling
   risk below.
2. **Guarantee, and the infeasible verdict.** A witness provably satisfies the intent within the
   bound; constrained-random *probably* hits it. And when the intent cannot be satisfied, rvprobe
   says `Infeasible` — a result HAVEN structurally cannot produce. That verdict is information: the
   target is unreachable within k cycles, so either the intent misreads the spec or the logic is
   genuinely dead. HAVEN's Bayesian loop has no such signal and simply spends more simulation on it
   (visible in today's data as the designs that burned their full iteration budget without moving).

**So the honest claim is not "we added type checking."** It is: the model states goals instead of
plans, the solver supplies the path, and the type system makes a mis-stated goal fail early and
locally rather than late and diffusely.

### The ablation that settles it

Because this is the reviewer's question, one experiment must answer it directly — a 2×2 that
separates the two contributions instead of asserting them:

| | LLM plans the steps | Solver derives the trace |
|---|---|---|
| **Errors only from the simulator** | ≈ HAVEN (the baseline we measured) | isolates the *solver* contribution |
| **Errors from the type system** | isolates the *type-check* contribution | rvprobe as built |

The two off-diagonal cells are cheap to build — suppress scalac diagnostics and feed back only VCS
errors for one; drive a step-sequence generator through the same typed surface for the other — and
they convert "is it just type checking?" from an argument into a number.

## The complementary study: residual closing

Alongside the head-to-head, the sharper result is where directed generation should dominate — and
the run we just finished gives us the residuals for free:

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
                     →  JasperGold covers C             ← the solver constructs the trace
                     →  witness → AbstractStimulus → replay under VCS -cm
                     →  URG again: did the item close?
```

Each stage already exists except coverage. Note what is different from HAVEN at stage 3: an
ill-formed intent fails at compile time with a located message that feeds the repair loop, and a
well-typed intent cannot produce an incoherent stimulus because the solver, not the model, builds it.
That is the mechanism the weak-model result is supposed to expose.

## The blocker: intents can only reference IO

Every intent we have written references the wrapper's `io.*` or the instantiated DUT's *ports*
(`instance.io.done`, `instance.io.sum`). None references an internal register or FSM state. That is
not an accident of the examples — it is the only thing the DSL can currently name.

This is a blocker for the residual-closing study above, because **coverage holes are internal**: an
unhit FSM state, an untaken branch inside a submodule, a register that never takes a value. "Reach
state S" is not expressible today; only "drive these inputs, observe these outputs" is.

It also explains a redundancy the code review flagged independently: `Sem.value` and `Sem.state` were
structurally identical wrappers. Of course they were — both can only reference ports, so 状态语义 had
nothing distinct to point at. The kind only becomes meaningful once it can name design state.

**The good news is that the limit is in the naming, not in the model.** The solver already reasons
over internal state: BMC traces carry `instance/fp_active_state`, `instance/int_done_pipe_state`, and
the fake-history soundness bug we fixed was *about* internal registers. Two proven mechanisms exist:

- **`MlirInvariant` on the `harvest` branch** already emits `verif.assume` referencing internal
  signals by their preserved `%name`, through a `Resolver` mapping signal name → SSA reference and
  width; registers keep their `%name` through `circt-verilog --ir-hw`. Our own `MlirBmc.pinFirregs`
  likewise rewrites internal registers by name, so the plumbing is in the repo already.
- **`circt-bmc --flatten-modules`** puts every instance in one namespace, which is how a constraint
  crosses a module boundary to reach a submodule's state.

**The design question for the DSL** is how to name internal state *without* giving up the typing that
is the paper's contribution. A raw string path (`"instance/state"`) works and is what the harvest
code does, but an unchecked string in a typed DSL is exactly the wrong trade. The right shape is a
typed accessor carrying a symbolic path, resolved and **checked at elaboration** against the DUT's
actual signal set — a mistyped or non-existent state name should fail where every other malformed
intent fails, at compile time, not as a silent no-op in the solver.

This becomes build item 0; the study cannot run without it.

> **Decision (2026-09-02): no internal-state references. The DSL's only objects are IO ports.** The section above
> is kept as the record of the question; the answer is that an intent constrains what is driven into the DUT and
> what is observed on its outputs, and reaching an internal condition is the solver's route-finding. Three reasons.
> First, the evidence did not need it: the ALU's port-form intents closed every reachable executable-line residual,
> and JasperGold proved the two lines nobody closed structurally unreachable (`alu_deadcode_formal.sv`). Second, the i2c
> byte_ctrl FSM is driven entirely by the command register's STA/STO/RD/WR/ACK bits and observed through SR, so every
> protocol transition is reachable as a *transaction-flow* intent — a temporal chain of Wishbone accesses with
> solver-chosen waits — with no reference to `c_state`. Third, the real limit on i2c is bound depth (a command at
> prescaler zero is ~50 cycles), which naming a state would not shorten. The typed accessor / port-promotion
> machinery (`feef840`, reverted in `0ebc2fb`) stays out. `Sem.state` survives as the predicate over the DUT's
> observation face — same shape as `Sem.value`, opposite provenance — which is exactly the plan/goal axis the 2×2
> ablation measured. Build item 0 is replaced by **flow intents** (`HavenI2cFlowUT`), see
> `rvprobe-in-haven-testbench.md`.

## What must be built (five items, in order)

0. **Internal-state reference** — per the section above: a typed accessor for design state resolved
   at elaboration, lowering to a constraint over the internal signal (harvest's `Resolver` is the
   working precedent, `--flatten-modules` the cross-boundary mechanism). Without it "reach FSM state
   S" cannot be stated, and the residual-closing study has nothing to aim at.

1. **The bulk-stimulus path** — needed for the head-to-head, and the reason the objection above
   lands: repeated solves under blocking constraints (`assume` the previous solution away) plus
   witness concatenation, so one run produces a stimulus stream rather than a single trace. The `ut`
   branch's rejection-sampling CRV (`e6b4d2f`) is the prior art to port forward.
2. **The coverage leg** — the one real gap (item 5 of the infra list, never built). Cheaper than it
   looks: `Driver.topString` emits plain SystemVerilog (`always #5 clock`, a DPI-C tick callback), so
   the Model B testbench runs under VCS unmodified. Needed: a `vcs -sverilog -cm
   line+branch+tgl+fsm+cond` build path beside the Verilator one, and a URG report parser. Both the
   FHS wrapper (`snps-fhs-env` + `snps-shell`) and the URG output format are already understood from
   today's HAVEN run — reuse them so *both arms are measured by the identical toolchain*.
3. **Multi-drive stimulus** — HAVEN's designs have wide input sets. Packing everything into one
   ≤64-bit word worked for ALU and SPI but does not scale (and the DPI return is width-limited).
   `AbstractStimulus` already carries every drive port per beat; what is missing is a codec and a
   tick callback that fill several ports — a columnar stimulus file plus a multi-output DPI callback.
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

Build items 0-2 (internal-state reference, bulk stimulus, coverage), wrap those three by hand, and run both the head-to-head
and the residual-closing loop on them. That is enough
to know whether the full sweep is worth it, and it produces the paper's core figure either way.

## The second axis: strong vs weak model

Everything above should be run with both a frontier model and DeepSeek V4 Flash. HAVEN's weak-model
profile is now known — parity on simple designs, collapse on complex ones (i2c −26.8, sdram −59.0,
three designs failing outright). The prediction under test: **the typed DSL narrows that gap**,
because the failure HAVEN suffers (semantic incoherence discovered only at VCS compile time, then
five exhausted repair attempts) is converted into a located type error the model can act on. If the
gap does not narrow, that is a real negative result and worth knowing before the paper commits to the
claim.
