# rvprobe stimulus inside HAVEN's testbench (updated 2026-09-04)

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

- Ten baseline intents, one per opcode: `Sem.value((io.op === k) & io.start)`. The residual-closing
  loop described below uses JasperGold for every newly generated candidate.
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
| rvprobe, volume fill | 10,000 | 10 | 79.41 | 89.42 | 77.36 | **94.85** | 78.46 |
| + 6 hand-written residual intents | 10,006 | 16 | 90.40 | 93.12 | 86.32 | 96.02 | 86.15 |
| **+ 5 LLM-written residual intents** | **10,011** | **21** | **94.78** | **98.94** | 89.62 | 96.72 | 93.85 |
| HAVEN (4 initial sequences + 2 improvement rounds) | 10,110 | — | 95.87 | 97.88 | 92.92 | 97.28 | 95.38 |

This table is the archived first run. The current JasperGold rerun is reported over the DUT-only pinned scorer in
the loop section below; its machine-readable record is `data/alu-jg-loop.json`.

Scope and metric are the same on both rows: the whole elaborated hierarchy, scored over
line/cond/toggle/branch. URG infers no state machine in this design, so no FSM column exists to
disagree about — unlike i2c below. HAVEN's row is its best iteration (95.87); ours is the final one,
which is also our best, since every round here was monotone. Scoring the saved final report of each
side over the DUT module alone (`urg_score.py … alu_top`) gives 94.73 vs 95.31, against 94.78 vs
95.39 for the full hierarchy of those same two reports — so the testbench modules are not carrying
the comparison, and HAVEN's advantage is 0.6 points final-to-final or 1.1 against its best round.

**The plumbing works**: solved stimulus runs inside a foreign testbench with only the sequence layer
replaced, measured by an identical toolchain.

**Volume is not a limitation of the approach.** The first row's 44.5% was an artifact of replaying one
witness per intent, not a property of directed generation. Because the intent is a constraint, the
solver only has to supply the *structure* — ten BMC calls, once — while the simulator fills the free
data fields, and the transaction count becomes free. Toggle coverage moves 8.01 → **94.85** under
exactly this change, confirming the earlier diagnosis that the toggle gap was a volume artifact.

**Closing the residual works, and it is cheap.** Running URG with `-metric` exposes the uncovered
items, and for this design they were unambiguous: 20 uncovered lines, essentially all FP corner cases
— `s1_is_nan <= 1'b1`, `fp_result <= {s3_inf_sign, 8'hFF, 23'h0}` (infinity), `fp_flags[FLAG_ZERO]`,
`fp_flags[FLAG_OVERFLOW]`, denormal handling. Six intents naming those operand patterns (NaN, ∞+∞,
∞−∞, x−x, max+max, denormal±) added **six transactions** and moved the score **79.41 → 90.40**.
That is 1.8 coverage points per transaction, against 0.0035 for the bulk fill — the tail-closing
property the approach claims, measured rather than asserted.

**The remaining gap is 5.5 points, and the residual says what it is.** Thirteen uncovered lines remain, and they are a
tighter cluster than before: FP rounding/normalization (`s4_new_exp`, `s4_final_frac`,
`s4_round_up`), underflow (`fp_flags[FLAG_UNDERFLOW]`), and pipeline teardown (`fp_active <= 1'b0`).
These need operand pairs whose *arithmetic result* lands in a rounding or underflow case — a harder
intent to state than "a is NaN", and exactly the kind where naming the destination and letting the
solver find the operands should pay off.

## The DeepSeek/JasperGold loop (2026-09-04)

DeepSeek V4 Flash was given the 13 remaining uncovered lines with their RTL context, the opcode table, and the
typed intent file's exact shape. `experiments/alu_residual_loop.py` saves that instantiated prompt and the raw
reply, typechecks the Scala through `ut_harness.py`, rejects any response that tries to put `circt-bmc` back in the
flow, and calls JasperGold for each candidate. The generated UVM sequence is then replayed in HAVEN's VCS bench;
that replay, not the existence of a formal witness for a fixed input, decides whether a coverage item closed.

Round 1 compiled on the first attempt for 50,347 tokens and produced six one-cycle candidates:

| intent | op | a | b | JasperGold |
|---|---|---|---|---|
| `fp_add_round_carry` | FP_ADD | `0x3fffffff` | `0x40000000` | covered, 6.464 s |
| `fp_sub_underflow_zero` | FP_SUB | `0x00800001` | `0x00800000` | covered, 6.392 s |
| `fp2int_nan` | FP2INT | `0x7fc00000` | `0x00000000` | covered, 6.468 s |
| `fp2int_small_zero` | FP2INT | `0x3f000000` | `0x00000000` | covered, 6.423 s |
| `and_default_fp_inactive` | AND | `0x00000000` | `0x00000000` | covered, 6.189 s |
| `reserved_opcode_default` | `0xA` | `0x00000000` | `0x00000000` | covered, 6.437 s |

A same-build, same-seed (`+ntb_random_seed=1`) A/B replay gives:

| replay | score | line | cond | toggle | branch | uncovered executable lines |
|---|---:|---:|---:|---:|---:|---:|
| baseline | 90.27 | 166/179 (92.74) | 183/212 (86.32) | 1837/1916 (95.88) | 56/65 (86.15) | 13 |
| + round 1 | 94.73 | 177/179 (98.88) | 190/212 (89.62) | 1850/1916 (96.56) | 61/65 (93.85) | 2 |
| + round 2 | **95.00** | 177/179 (98.88) | 192/212 (90.57) | 1853/1916 (96.71) | 61/65 (93.85) | 2 |

Round 2 fed only those two remaining lines back to DeepSeek. Its first attempt (16,712 tokens) emitted two more
one-cycle candidates, both solved by JasperGold: `f2i_shift_ge_32` (which duplicated round 1's FP2INT 0.5 input)
and `not_default_fp_inactive`. Replay improved condition and toggle coverage, but neither executable line moved.

That is a real termination result, not an input-shape excuse. The exact path conditions were stated as cover
properties in `experiments/haven_tb/alu/alu_deadcode_formal.sv`; JasperGold proved both **unreachable** in 0.01 s:

- line 336 is the default arm of the FP counter case. While `fp_active` is true, the counter can only visit
  1, 2, 3 and 4; state 4 clears `fp_active`.
- line 401 requires `127 <= f2i_exp < 158` and `158-f2i_exp >= 32` simultaneously. The first condition limits the
  difference to 1..31.

The loop therefore closes at **95.00**, with all reachable executable lines exercised and the two remaining URG
items formally classified as dead code. Across both rounds DeepSeek used 67,059 tokens; the eight candidate
JasperGold calls took 51.064 s in total.

## The second design: i2c, where the approach loses

ALU is a datapath: an intent names operands, and one transaction reaches the target. i2c is a
protocol, and it is the design where HAVEN's DeepSeek run collapsed (63.2 against GPT's 90.0), so it
is the case the approach should win. It does not.

13 solver calls — eight register writes, four residual-class scenarios (reset pulse, clock stretch,
arbitration loss, full transfer), one status poll — expand to ~8,800 transactions through the same
`UvmSequence` codec, in HAVEN's unmodified i2c testbench, scored by URG with **HAVEN's own five-metric
command line** (`line+branch+tgl+fsm+cond`), which required recompiling our arm with `-cm …+fsm`
because our first run had not instrumented FSM at all:

| | txns | line | cond | toggle | fsm | branch | score |
|---|---|---|---|---|---|---|---|
| rvprobe, 13 intents | ~8,800 | 85.76 | 53.91 | 72.59 | **41.67** | 83.61 | **67.51** |
| HAVEN / DeepSeek | 13,087 | 86.39 | 58.26 | 83.28 | **66.67** | 81.15 | **75.15** |

DUT-only (the three `i2c_master_*` RTL modules); whole-hierarchy figures are 57.59 vs 63.24, the
latter reproducing HAVEN's published i2c number exactly, which is the check that the two are being
measured the same way.

**We lose by 7.6 points, and FSM transition coverage is over half of it** — 20 of 48 transitions
against HAVEN's 32. Per module, the split is sharp: rvprobe leads on `i2c_master_top` (+2.7) and
`i2c_master_bit_ctrl` (+2.4), and loses `i2c_master_byte_ctrl` by **19.6**, uniformly across all four
metrics. byte_ctrl is the state machine that sequences START → address → data → ACK → STOP, and every
intent we wrote is a *single register write*. A single write cannot walk a transition graph, so the
scenarios that do get written stop at the first ACK.

This is the honest shape of the result, and it is more useful than parity would have been: the loss
is localized to one module, attributable to one property of the intents (single-beat, not
multi-phase), and measured on the metric that names it. The fix is a transaction-flow intent —
multi-byte writes, reads, repeated START, STOP — which is what the temporal kind exists for and what
the ALU experiment also did not exercise. Until that is written, the claim this arm supports is
"competitive on datapaths, not yet on protocol state machines", not parity.

An earlier version of this comparison reported "85.8 vs 86.4, winning two of three modules". Those
were *line* coverage on the DUT modules — a real number, but not the score used for ALU, and chosen
where our arm happened to be closest. Both arms are now scored by one pinned rule, which is what
`experiments/urg_score.py` exists to make cheap.

## The flow arm: i2c, revisited (2026-09-02)

The fix named above — transaction-flow intents — is `HavenI2cFlowUT`: a temporal chain of the five register writes
that launch one command (prescaler, enable, transmit byte, command), one instance per command byte, plus a
reset-midway variant that asserts `arst_i` a solver-chosen number of polls after the command. Nothing references
`c_state`; the decision that intents name IO ports only (`rvprobe-arm-design.md`) held, and byte_ctrl's whole
transition graph is driven by the command register's STA/STO/RD/WR/ACK bits.

Seven intents, all solved by circt-bmc (`experiments/I2cFlowDriver.scala`, total solver time under nine seconds). Each
write carries the ACK term and a poll sits between writes: with stb/cyc held high the core acknowledges on alternate
cycles, so a back-to-back chain would be half dropped by the model while the replay driver's handshake lands every
write — see the JasperGold note below for how that was found.

| intent | CR | bound | solve | witness |
|---|---|---|---|---|
| wr_sto (START·WRITE·ACK·STOP) | 0xD0 | 12 | 0.59 s | 11 beats |
| rd_sto (START·READ·NACK·STOP) | 0xE8 | 12 | 0.57 s | 11 beats |
| wr (WRITE·ACK, no START) | 0x10 | 12 | 0.57 s | 11 beats |
| rd (READ·ACK) | 0x28 | 12 | 1.96 s | 11 beats |
| sto (STOP) | 0x40 | 12 | 0.57 s | 11 beats |
| rst_wr, rst_rd (reset mid-transfer) | 0xD0 / 0xE8 | 28 | 1.93 s | 27 beats, gap 1 |

Each is expanded by **timing fill**, the time-axis twin of the ALU's pinned-field randomization: an intent is a
constraint and the witness one solution, so the replay varies what the intent left free. For the command flows that
is the wait after the command — sixty status polls appended so the transfer completes before the next flow. For the
reset intents it is *when* the reset lands: the witness's gap of one poll is swept over 0..59, and with prescaler 2
one bit phase is three clocks, exactly the Wishbone driver's cost per transaction, so the sweep visits every phase
of the transfer. 350 + 5220 beats from seven solver calls.

Same testbench, same VCS, same URG, same five-metric rule over the three DUT modules (`data/i2c-flow.csv`):

| | score | line | cond | toggle | branch | fsm |
|---|---|---|---|---|---|---|
| 13 single-beat intents (the row above) | 67.51 | 85.76 | 53.91 | 72.59 | 83.61 | 20/48 |
| + 5 command flows | 80.42 | 99.05 | 56.52 | 83.97 | 95.90 | 32/48 |
| + reset sweep | **87.50** | 99.05 | 57.39 | 84.31 | 95.90 | **48/48** |
| HAVEN / DeepSeek | 75.15 | 86.39 | 58.26 | 83.28 | 81.15 | 32/48 |

Hand-written equivalents of the same seven flows (written first, to separate the intent design from the solver)
score 87.33 with the same 48/48; the solver-derived sequences differ only in the polls between writes. That is what
"the solver supplies the structure" should mean.
Two things the split says. The 32 transitions the flows reach are exactly HAVEN's 32 — every protocol-path transition
of byte_ctrl and bit_ctrl follows from the command byte, and this is the ceiling of any arm that drives only the
Wishbone side. The other 16 are all "mid-transfer → idle", the reset branches, and HAVEN cannot reach them because its
generated driver never assigns `arst_i` (the `seq_item` field exists; the `drive_item` line does not). Here `arst_i`
is an input like any other, and a reset placed by an intent and swept by the replay closes all of them.

**What the solver does not do here, stated plainly.** The flow intent was first written with its completion in the
model — `… ### (poll & !IF)[*1:60] ### (poll & IF)` at bound 68 — and circt-bmc gave no verdict in 3h10m; the same
wait as a bare `##[1:60]` delay gave none in 1h55m (`data/i2c-flow.json`, `bmc_cost_probes`). A command at prescaler
zero is ~50 cycles of a three-module design with 16-bit counters, and that depth is past what this engine solves in
practical time. So the model proves the *structure* (the writes are well-formed and in order, the reset falls after
the command with reset released around it) at bound 8–24, and the simulation observes the completion. This is the
scaling boundary the arm design said to report rather than hide: directed generation on this design is a solver for
the stimulus's structure and a simulator for its duration.

**JasperGold, the second backend.** The host has JasperGold 2021.03 licensed (VC Formal is installed but its run-time
license is not), and `utlib/src/JasperGold.scala` drives it: firtool's single-file SystemVerilog of the UT, the
vendored RTL, a Tcl script, and the witness read back from VCD into the same `Trace` circt-bmc's parser produces
(`experiments/haven_tb/eda-shell` supplies the license environment). The flow *with its completion in the model* —
five acknowledged writes, a poll between each, then polls until SR.IF is read back with SR.AL clear — is a
**221-cycle witness in 11.3 s** (`HavenI2cFlowJgTest`): command at cycle 7, interrupt flag at 218, arbitration never
lost. That is the property circt-bmc could not decide in hours.

Getting there took four corrections, each found by the engine in seconds, and each a real property of the intent
or the design rather than of the engine:

1. *The first "deep witness" was arbitration loss.* The `[*52]` assertion form's 67-cycle counterexample walks
   START·WRITE·STOP and then sets `al` at cycle 62: at prescaler zero one bit phase is a single clock, shorter than
   bit_ctrl's input synchronizer, so the looped-back master reads its own previous bit and loses arbitration to
   itself; `irq_flag` rose from that, not from `done`. A VCS simulation of the same wrapper does the identical thing
   (al at cycle 47 at prescaler 0; done at cycle 232 at prescaler 2), so the model is faithful and prescaler 0 is
   simply below what this core needs. Flows now run at prescaler 2 and complete only with SR.AL clear.
2. *Writes must carry the ACK term.* With stb/cyc held high the core acknowledges on alternate cycles, so a chain of
   back-to-back writes is half dropped; the witness pre-wrote the dropped registers in its free prefix.
3. *The completion anchor must follow a constrained beat.* `DAT` lags the address by a cycle; after a free beat it
   can be the prescaler's 0xFF byte, whose bit 0 looks like IF (`##[1:60]` form, 0.09 s).
4. *A flow pinned to cycle 0 is unsatisfiable*, because nothing is acknowledged the cycle after reset. Stated as an
   assertion (`assert property (not (S))`, what circt-bmc needs) this sat `undetermined` for 600 s at every
   prescaler; stated as what generation is — `cover property ((S))` — JasperGold proved it `unreachable` in 2 s. The
   backend therefore rewrites the generation assertion into a cover before analysis: a covered scenario is the
   witness, an unreachable one is `Infeasible` with an unbounded proof behind it.

With the backend in place `I2cFlowDriver` runs the whole arm on JasperGold when an engine is reachable: the five
flows now carry their own completion (222, 222, 179, 179 and 27 beats at prescaler 2; 7–12 s each) and the two reset
intents solve in 6–8 s, about 65 s of solver time in all, and the replay needs no timing fill. HAVEN's testbench
scores it **87.50, FSM 48/48, line 99.05** — the same numbers as the circt-bmc arm with polls appended
(`data/i2c-flow.csv`, `solver-jg`), which is the right outcome: what changed is that the wait is proven, not padded.
One more alignment bug surfaced on the way and is worth recording because coverage caught it: the VCD JasperGold
writes opens at time 0 with the clock already high, the reader took the first edge at time 10 instead, every flow
lost its prescaler write, and the arm scored 78.04 with 31/48 until the first beat was kept.

**Shapes only the second backend takes.** The flow's wait can be written without a bound at all, and in three
different SVA idioms the DSL already has: an unbounded repetition (`(calm & !IF)[*1:$]`), `throughout` over an
unbounded delay (`calm throughout (calm ##[+] (calm & IF))`), and a goto repetition that insists the transfer be
*seen* in progress (`calm throughout ((calm & TIP)[->3] ##[+] (calm & IF))`). JasperGold returns the same 222-cycle
witness for each in 8–11 s (`HavenI2cFlowJgTest`); circt-bmc fails to lower every one of them (`failed to legalize
operation 'verif.assert'`, `HavenI2cFlowTest`). The DSL did not change between the two engines — the same
`Sem.temporal` chain lowers to the same SVA — which is the claim the second backend exists to support: the typed
intent is engine-independent, and what an engine can take is the engine's boundary, not the language's.

`wb_ack_o` has no reset in this RTL; a simulation that holds stb/cyc high keeps it X forever (HAVEN's driver
deasserts them between transactions, which is what resolves it). JasperGold's multi-engine race is also not
deterministic run to run — a cover found in 1.5 s once took over 240 s on the next attempt — which is worth
stating in any timing table. Details and the engine log excerpts: `data/i2c-flow.json`, `jaspergold_probes`.

## The third design: sdram, and what HAVEN's 11.0 actually was (2026-09-02)

The SDRAM controller (OpenCores sdr_ctrl, 11 files, two clock domains, a bidirectional data pad) is where HAVEN's
reproduction collapsed to **11.02**. Two bugs in its generated bench explain that number, and neither is about
stimulus: the interface declares `logic sdram_clk` and nothing drives it (the seq_item lists the SDRAM clock as a
random bit), so the SDRAM side never clocks; and the generated SDRAM-side agent's driver zeroes all thirteen static
configuration pins at start (`cfg_sdr_en <= 0` among them), so the controller is disabled even once it clocks.
With the clock tied and the 26 zeroing assignments removed — one line and one deletion, both declared in
`experiments/haven_tb/sdram/` — HAVEN's own eighteen sequences score **70.59** (FSM 23/41) instead of 11.02.

Our arm on the same fixed bench is five flows (`HavenSdramFlowUT`, `experiments/SdramFlowDriver.scala`): the
controller's initialization, a write, a read, a same-bank row change and the periodic refresh, each an unbounded
SVA chain over the Wishbone ports and the SDRAM command bus, solved on JasperGold in 7–38 s (the refresh witness is
3,132 cycles long), replayed through the strobe-aware codec — a held request is one transaction, an idle stretch a
wait. The wrapper needs no import surgery beyond splitting the data pad (`sdrc_top_split.v`); the eight
asynchronous resets, `initial` blocks and `$display`s that would have needed model-prep for circt-bmc go through as
they are.

| arm | score | line | cond | toggle | branch | fsm | sim cycles |
|---|---|---|---|---|---|---|---|
| HAVEN as published (bench bugs in place) | 11.02 | 13.78 | 21.75 | 12.72 | 0.00 | 0/41 | — |
| HAVEN, clock fixed | 25.15 | 49.17 | 30.85 | 9.10 | 36.65 | 0/41 | 1.24M |
| HAVEN, clock and configuration fixed | 70.59 | 81.82 | 66.48 | 71.89 | 76.65 | 23/41 | 201k |
| rvprobe, 5 flows, same fixes | 63.44 | 81.82 | 63.26 | 43.34 | 75.14 | 22/41 | 4.1k |
| rvprobe, 8 flows (+ turnarounds, enable withdrawn) | 65.02 | 81.82 | 64.64 | 43.79 | 76.30 | 24/41 | 5.3k |
| rvprobe, 8 flows + volume fill (500 per request) | **71.03** | 81.82 | 65.65 | 72.48 | 76.65 | 24/41 | 148.7k |

Five solved transactions match eighteen generated sequences on line (identical), branch and FSM, at a fiftieth
of the simulation; toggle is where volume shows, and it is the one metric the ALU experiment's pinned-field
randomization exists for. Three more flows — write after read, read after write, and the enable withdrawn
mid-operation and restored (the one flow that moves a configuration pin, which needs the generated driver to drive
the enable from the item: a third declared line) — take the FSM to 24/41, past HAVEN's 23; the fill — the write and
read requests with direction, byte enables, cycle type and enable pinned, address and data randomized, five hundred
transactions per solved request — takes toggle from 43.79 to 72.48 and the score to **71.03**, above HAVEN's fixed
70.59, with eight solver calls. The refresh witness took 295 s on this run against 38 s on the previous one:
JasperGold's engine race is not deterministic, and the table reports single runs. The FSM residual is nearly the same set on both sides (`data/sdram-flow.json`,
`missing_fsm`): read-after-write and write-after-read transitions in the transfer controller, a burst crossing a
page, the bank machine's ACT path — every one a flow over ports not yet written — and seven power-up transitions
that need `cfg_sdr_en` deasserted mid-operation, which the generated driver cannot do because it never drives the
configuration pins at all. Two of the engine's verdicts on the way were worth keeping: the read flow stated with
its READ command after the acknowledge is unreachable (proven in 530 s — a read's acknowledge carries data, so the
command precedes it), and the first precharge flow was satisfied by the initialization's own PRECHARGE, with both
writes still queued — an underspecified anchor, fixed by demanding the first WRITE on the bus before the second
request.

## The fourth design: can, where HAVEN has no testbench and the engine meets frame-level interaction (2026-09-03)

HAVEN produced no compiling bench for the OpenCores CAN controller in five repair attempts. The cause is one
missing field: the generated `seq_item` lacks the `wb_dat_o` the template's own monitor and driver read, and behind
it the generated sequences carry SystemVerilog syntax errors (`inside [a:b]` without braces). With the field added
and the sequences excluded — both declared in `experiments/haven_tb/can/` — the infrastructure compiles and runs.

Our arm (`HavenCanUT`, `experiments/CanFlowDriver.scala`) wraps the controller with no surgery; the bus is a
wired-AND of `tx_o` and what the *other* node drives, and that node is the engine (the vendored `.v` files are
analyzed as Verilog-2001 because `can_ibo.v` has a port named `do`, the same reason HAVEN passes VCS a 2001
extension flag). Two flows solve: leaving reset with the bus timing programmed (51 cycles, 7 s) and a frame
requested and started — the transmit buffer and command written, `tx_o` seen dominant (95 cycles, 10 s). Replayed
through the bench's Wishbone driver, thirteen transactions score **50.47** (line 79.28, branch 69.12; URG infers no
state machine in this core, so four metrics) against HAVEN's nothing (`data/can-flow.csv`).

Three flows do not solve, and the record of why is the result (`data/can-flow.json`, `engine_limits`). The
acknowledged transmission — the interrupt register's TI bit, which only a successful frame sets — sat undetermined
for 900 s with the other node's drive free, and again with that node reduced to an acknowledge-once model that
leaves the engine one choice, the moment. A received frame (the RI bit) needs the engine to construct a CRC-correct
frame: undetermined. Bus-off through traffic: undetermined; stated without a transmit request it is *unreachable*,
proven in 10 s, because only the transmit error counter reaches 256 — and a bind cover shows the counter itself
reachable in six cycles by the register write reset mode allows, the shortcut the flows' pinned writes exclude.
Two earlier anchors were satisfied trivially and are worth recording as traps: `irq_on` is the core's active-low
`irq_n`, and the interrupt *line* is raised by an error frame the engine provokes within four cycles of the start
of frame — completion must be read from the interrupt register. So on this design the engine-as-partner reaches
register-level and frame-start behaviour and stops at frame-level interaction; a protocol partner model — what
HAVEN's BFM is — is the remedy, and that is the boundary to state.

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

Artifacts: `experiments/alu_residual_loop.py` (the maintained prompt/typecheck/JasperGold entry),
`experiments/haven_tb/alu/` (the dead-code proof), `docs/date2027/data/alu-jg-loop.json` (the measured result),
`utlib/src/UvmSequence.scala` (the codec), and `stdlib/src/HavenAluUT.scala` (`HavenAluFpUT`).

## Footnote: what HAVEN's improvement loop actually did

Worth stating precisely, because "15 sequences + 3 BO iterations" overstates it. The ALU run's
progression:

| round | coverage | sequences |
|---|---|---|
| 0 (initial simulation) | 87.16 | 4 |
| 1 | **95.86** | 9 |
| 2 | 95.39 | 12 |

Four sequences came from the spec; the loop added five (+8.7 points) and then three more that made it
*worse* (−0.5), with the best snapshot preserved. It saturated after one productive round and
regressed on the next, each round costing a full simulation plus LLM and Bayesian-optimization work.

The current DeepSeek/JasperGold loop moved 90.27 → 94.73 in its first six candidates and 95.00 after the second
round. It then stopped on proof rather than repeated guessing: both remaining executable-line items are formally
unreachable. The contrast is worth making: tuning a *distribution* runs out of room once the reachable mass is
covered, while naming a *specific* uncovered item exposes either a closing stimulus or a dead-code obligation.
