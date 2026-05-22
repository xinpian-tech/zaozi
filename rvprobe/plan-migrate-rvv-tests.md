# Migrate riscv-vector-tests onto RVProbe

## Goal Description

Re-implement the per-instruction RVV unit-test generator currently at
`riscv-vector-tests/` (Go, ksco) on top of the RVProbe Scala 3 eDSL at
`zaozi/rvprobe/` (branch `dev`, commit e9a6a58). The migration is
semantic, not literal: the upstream `.toml` value tables are replaced by
a tuple-level predicate vocabulary. Predicate-to-vector-data lowering is
a **pure function** outside the SMT solver — no Solver calls, no retry
loops, no SMT model variables introduced. The SMT solver continues to
handle only opcode selection (stage 1) and scalar-arg selection
(stage 2). spike, pspike, testfloat3, macros, env, single, and the
Makefile are reused as-is; only the Makefile's generator-invocation line
is swapped per DEC-3. FP operand generation remains testfloat3-driven
per DEC-4. The release-blocking validation matrix is
{(VLEN=256, XLEN=64, full march), (VLEN=128, XLEN=64, full march)} per
DEC-6.

## Acceptance Criteria

### Schema inventory (the 39 unique upstream operand-format strings, partitioned)

| Category   | Count | Phase | Examples |
|---|---|---|---|
| vsetvl*    | 3     | 7     | `vsetvli`, `vsetvl`, `vsetivli` |
| FP         | 5     | 8     | `fd,vs2`; `vd,fs1`; `vd,fs1,vs2,vm`; `vd,vs2,fs1,v0`; `vd,vs2,fs1,vm` |
| Load/store | 8     | 9     | `vd,(rs1)`; `vd,(rs1),vm`; `vd,(rs1),rs2,vm`; `vd,(rs1),vs2,vm`; `vs3,(rs1)`; `vs3,(rs1),vm`; `vs3,(rs1),rs2,vm`; `vs3,(rs1),vs2,vm` |
| Integer    | 23    | 6     | `vd,vs2,vs1,vm`; `vd,vs2,rs1,vm`; `vd,vs2,imm,vm`; `vd,vs2,uimm,vm`; `vd,vs2,vm/2`; `vd,vs2,vm/3`; `vd,vs1`; `vd,vs2`; `vd,imm`; `vd,vm`; etc. |
| **Total**  | **39**|       | |

The table is the single source of truth for schema accounting. AC-1 is
verified against it; AC-18 freezes it as an artifact during Milestone 1.

### Criteria

- AC-1: **Schema coverage.** Every upstream `format = "..."` string maps
  to exactly one Scala schema in `Schema.scala` (39 total). The 3
  vsetvl* schemas live in the `vsetvl/Tests.scala` codepath, not in the
  ordinary-instruction `Schema.scala`.
  - Positive: walking `riscv-vector-tests/configs/` and collecting every
    `format = "..."` string yields a set that is a subset of the
    39-entry Schema sealed family.
  - Negative: a fictitious 40th format string in a config file fails
    schema lookup with an explicit unknown-format error rather than
    silently being skipped.

- AC-2: **Instruction coverage.** Every upstream
  `configs/<ext>/<name>.toml` has exactly one matching declaration
  under `zaozi/rvprobe/src/rvv/insns/`, keyed by (extension, name).
  Duplicates (`vfncvt.f.f.w`, `vfwcvt.f.f.v`) have two declarations,
  one per extension.
  - Positive: 676 declarations exist, each linked to its source toml
    path via a generated index file.
  - Negative: adding a config with no matching declaration fails the
    audit pass with the unmatched toml path listed.

- AC-3: **Predicate audit forward.** Every literal-tuple in every
  upstream toml's `base`/`sew*`/`fsew*`/`bf16sew*` array is either
  (a) classified into at least one `TuplePred` (with
  `Lit(BigInt, rationale)` as the named escape hatch for arbitrary
  corners), OR (b) explicitly waived per AC-13c's governance process.
  Classified and waived are the two non-failing outcomes; they do not
  overlap. Unclassified-and-unwaived count is zero.
  - Positive: audit script emits per-literal classification report;
    all 676 tomls process cleanly with every literal landing in the
    classified set or the waived set.
  - Negative: removing a predicate that classifies some non-waived
    literal causes audit to fail with the now-unclassified literal
    listed.

- AC-4: **Predicate audit backward.** Every non-`Lit`, non-`Random`
  predicate in `ValuePred`/`TuplePred`/`FpValuePred`/`FpTuplePred` is
  justified by at least one upstream literal-tuple classified into
  it. Dead vocabulary is flagged.
  - Positive: adding a new predicate fails AC-4 until a justifying
    upstream literal is documented or the predicate is removed.
  - Negative: a predicate with zero supporting literals is reported as
    dead.

- AC-5: **VTYPE envelope.** Every per-instruction lowering receives an
  immutable `VTypeEnvelope` (vl/SEW/LMUL/vta/vma) with `vill=false`.
  The legal-combination filter rejects illegal VType at envelope
  construction time, not at runtime. `vill=true` is exercised only
  inside `vsetvl/Tests.scala` per DEC-9.
  - Positive: constructing a VTypeEnvelope with (SEW=64, XLEN=32) is
    rejected up-front with a clear error.
  - Negative: a stale envelope reused across two tests fails a
    structural immutability check.

- AC-6: **Pipeline pass on release-blocking matrix.** For each (VLEN,
  XLEN, march) tuple in the release-blocking matrix per DEC-6 — namely
  (VLEN=256, XLEN=64, full upstream march) and
  (VLEN=128, XLEN=64, full upstream march) — the full pipeline
  stage1 → pspike → merger → stage2 → spike passes for every emitted
  test.
  - Positive: spike signature diff is empty across both
    release-blocking configurations.
  - Negative: a hand-introduced bug in one schema causes spike to flag
    one test as mismatched; CI reports the (schema, test) pair.

- AC-7: **CLI parity.** The rvprobe driver accepts `-VLEN`, `-XLEN`,
  `-split`, `-integer`, `-pattern`, `-stage1output`, `-testfloat3level`,
  `-repeat`, `-march` with semantics and defaults identical to
  upstream. `-configs` is handled per DEC-5.
  - Positive: `--help` output diffed against upstream surfaces no
    unknown or missing flags.
  - Negative: passing an unrecognized march substring errors
    identically to upstream.

- AC-8: **Magic word fidelity.** Every emitted magic `.word 0x...`
  decodes via an rvprobe-side pspike-decoder unit test (using the
  same encoding helper as emission) to the intended
  (vector_group, LMUL, vxsat) triple. The decoder unit test runs
  without spike.
  - Positive: 100% of emitted `.S` files have decodable magic words
    matching the intended encoding.
  - Negative: a deliberately corrupted bit-field in emission fails
    the decoder test with the bit-position and expected-versus-actual
    values reported.

- AC-9: **Makefrag parity.** Emitted `Makefrag` is sorted, uses
  `"tests = \\\n  <name> \\\n  ..."` format, and every listed target
  has a matching stage1 `.S` on disk.
  - Positive: `diff <(sort Makefrag-targets) <(ls stage1/*.S)`
    produces empty output.
  - Negative: removing one stage1 `.S` causes the Makefrag-versus-
    stage1 diff check to fail.

- AC-10: **CSR axis coverage.** For instructions with `vxrm=true` (22),
  all 4 vxrm modes are exercised; for instructions with `vxsat=true`
  (14), vxsat clear and set are exercised; for all FP instructions —
  including the 2 `notestfloat3=true` instructions
  (`vfredusum.vs`, `vfwredusum.vs`) — all 5 FRM modes are exercised.
  Per DEC-4, testfloat3 supplies FP operands for FP-with-testfloat3
  instructions; the 2 `notestfloat3=true` instructions take operands
  from Scala source-of-truth in `notestfloat3/Vfredusum.scala` and
  `notestfloat3/Vfwredusum.scala`. FRM sweep semantics are identical
  across both paths.
  - Positive: emitted tests for the 22 vxrm insns include 4 mode
    variants each; the 2 notestfloat3 insns each have an explicit
    Scala case-set table that exercises all 5 FRM modes.
  - Negative: dropping a vxrm mode coverage from one insn fails
    AC-10 with the (insn, missing-mode) pair reported.

- AC-11: **No-touch boundary.** No file under `pspike/`,
  `testfloat3/`, `macros/general/`, `env/`, or `single/` is modified.
  `Makefile` is modified only for the generator-path swap per DEC-3
  (typically a single line change).
  - Positive: `git diff` against the upstream-import baseline shows
    no changes under those directories and at most a single-line
    diff in `Makefile`.
  - Negative: any other edit there fails CI.

- AC-12: **TOML-free runtime.** No `.toml` file is read at runtime by
  `Driver.scala`. Audit snapshots under `audit/snapshots/` are not
  runtime inputs.
  - Positive: a file-open inventory during driver execution lists
    zero `.toml` reads.
  - Negative: a smuggled `.toml` read fails AC-12.

- AC-13a: **Tuple coverage.** Every upstream curator tuple is either
  classified into a predicate or appears on `audit/waivers.md` with
  owner, reason, date (ISO-8601), and tracking-ID fields populated.
  Unclassified-and-unwaived count is zero.
  - Positive: empty unclassified list at audit closeout.
  - Negative: one literal removed from any predicate's classification
    set without a waiver fails AC-13a.

- AC-13b: **Emitted-test coverage.** Every non-`Lit`, non-`Random`
  predicate is exercised by at least one rvprobe-emitted test (the
  dynamic counterpart to AC-4's static audit).
  - Positive: post-emission scan reports 100% predicate exercise.
  - Negative: removing a predicate's instance from any declaration
    in `insns/` fails AC-13b.

- AC-13c: **Waiver governance.** Each entry in `audit/waivers.md`
  has owner, reason, date (ISO-8601), and tracking-ID (issue or PR
  reference). No waiver lacks any field.
  - Positive: lint of `audit/waivers.md` passes.
  - Negative: an entry missing owner fails the lint.

- AC-14: **Duplicate-name disambiguation.** `vfncvt.f.f.w` and
  `vfwcvt.f.f.v` produce distinct stage1 outputs of the form
  `<insn>_<ext>-N.S`, preventing the silent last-wins bug present
  upstream.
  - Positive: both extension variants appear in stage1 with distinct
    filenames.
  - Negative: emitting only one variant (the upstream bug) fails
    AC-14.

- AC-15: **TOML-drift CI.** A CI check compares upstream `.toml`
  content hashes against `audit/snapshots/`. Failure mode: red CI
  until all three of (a) the pinned upstream commit hash is bumped,
  (b) the audit report is regenerated, AND (c) the predicate/`Lit`
  diff is human-reviewed and committed alongside.
  - Positive: at steady state, hashes match and CI is green.
  - Negative: an upstream curator change to one toml turns CI red
    until the three-commit pattern lands.

- AC-16: **Proof-of-concept gate.** Before broad schema fan-out, the
  following cases pass end-to-end (stage1 → pspike → merger →
  stage2 → spike) on the release-minimum configuration
  (VLEN=256, XLEN=64, full march):
  - 1× basic integer: `vadd.vv` at LMUL=1
  - 1× widening with LMUL stress: `vwadd.vv` at LMUL=4 (verifies
    register-group occupancy and dest=8-register reservation)
  - 1× mask-producing: `vmseq.vv`
  - 1× unit-stride load/store: `vle32.v` + `vse32.v`
  - 1× indexed load: `vluxei32.v` (verifies EEW separation and
    indexed EMUL calculation)
  - 1× segmented store: `vsseg2e32.v` (verifies
    NFIELDS × EMUL ≤ 8)
  - 1× saturating with vxrm: `vnclip.wv` (verifies vxrm sweep and
    magic-word encoding of vxsat bit)
  - 1× FP with FRM: `vfadd.vv` with all 5 FRM modes
    (testfloat3-supplied operands per DEC-4)
  - 1× vsetvli sweep: dedicated AVL encoding test
  Per-family cardinality budgets are measured and pinned during
  this milestone; budgets become hard upper bounds for AC-13b.
  - Positive: all 9 cases pass on the release-minimum config.
  - Negative: any case failing blocks fan-out to subsequent
    milestones.

- AC-17: **Register-group occupancy, overlap legality, and
  fractional-LMUL coverage.** Independent of spike, a Scala unit-test
  suite validates that the schema layer rejects illegal register-
  group choices: LMUL/EMUL occupancy violations (including
  fractional LMUL cases LMUL_F2 / LMUL_F4 / LMUL_F8), destination
  overlap with widening sources, mask-register-as-dest for masked
  operations, NFIELDS × EMUL > 8, and source/dest aliasing on
  vrgather/vcompress with the wrong LMUL. At least one schema is
  exercised at each of the 7 LMUL values (F8, F4, F2, 1, 2, 4, 8).
  - Positive: hand-constructed illegal combinations are rejected
    with structural errors; one passing schema instance exists at
    each of the 7 LMUL values.
  - Negative: a legal combination is rejected, or a fractional-LMUL
    case is silently skipped; weakening the check fails AC-17.

- AC-18: **Schema inventory artifact.** The 39-entry schema table is
  emitted as a generated artifact (e.g.,
  `audit/schema-inventory.md`) during Milestone 1 and re-verified
  during Milestone 12 to match the actually-implemented
  `Schema.scala` sealed family.
  - Positive: artifact matches `Schema.scala`'s enumeration at all
    times.
  - Negative: implementing a 40th schema without updating the
    artifact fails AC-18.

## Path Boundaries

### Upper Bound (Maximum Acceptable Scope)

All 13 milestones complete. All 18 acceptance criteria pass. The
release-blocking matrix per DEC-6 — both
(VLEN=256, XLEN=64, full march) and
(VLEN=128, XLEN=64, full march) — exercised end-to-end. All 676
upstream instruction declarations live in `zaozi/rvprobe/src/rvv/insns/`
keyed by (extension, name) per AC-14. The predicate vocabulary covers
every classifiable curator intent; `Lit(BigInt, rationale)` entries
exist where curator wisdom resists named-predicate classification per
DEC-1. The CI TOML-drift check (AC-15) is wired with the three-commit
refresh workflow. `riscv-vector-tests/` is retained or removed per
DEC-7. The chaining-matrix work on the `dev` branch is not modified by
this migration but can be re-pointed at the new RVV instruction
declarations afterward as a follow-up.

### Lower Bound (Minimum Acceptable Scope)

Milestones 1 through 5 complete: the schema family + VTYPE envelope
+ EEW/EMUL model exists (AC-1, AC-5, AC-18); the audit pass produces
the predicate vocabulary and snapshots (AC-3, AC-4 passing for the
classified portion, with explicit waivers tracked for residuals);
`ElemValueLowering` is a pure function (AC-12 demonstrably TOML-free);
`MagicInstrEmit` produces decoder-verifiable magic words (AC-8); the
Driver emits valid sorted Makefrag (AC-9) with stage1 `.S` files for
the 9-case POC; the register-occupancy unit-test suite covers all 7
LMUL values (AC-17); AC-16's POC gate passes on the release-minimum
configuration (VLEN=256, XLEN=64, full march); per-family cardinality
budgets are measured. AC-11's no-touch boundary on
spike/pspike/testfloat3/macros/env/single is preserved. The remaining
milestones (6–13) are tracked as known-incomplete with explicit
schema-by-schema gap lists.

### Allowed Choices

Can use:
- Scala 3 idioms: sealed traits, opaque types, given/using, enum types,
  match types where they reduce boilerplate.
- The existing `Statement.Word` / `word()` path for emitting
  `.word 0x...` magic instructions.
- The existing `AsmApi.scala` and `RVConstraints.scala` auto-generation
  scripts under `zaozi/rvprobe/src/scripts/`.
- The existing two-stage SMT solver in `RVGenerator.scala` for opcode
  selection (stage 1) and scalar-arg selection (stage 2).
- `java.util.regex` for the `-pattern` flag (covers all upstream regex
  syntax used).
- testfloat3 binaries invoked as subprocesses for FP operand generation
  per DEC-4.
- Spike + pspike + merger for golden-result oracle (unchanged).

Cannot use:
- TOML parser at runtime in `Driver.scala` (AC-12).
- New SMT model variables for vector-element data
  (`ElemValueLowering` is pure, deterministic, outside the solver).
- A "self-verifying" generator mode that pre-computes expected
  results — spike remains the oracle.
- Byte-equivalent `.S` claims with upstream — predicate-driven, not
  literal-preserving.
- Modifications to `pspike/`, `testfloat3/`, `macros/general/`,
  `env/`, `single/`, or any file under `paper/` (AC-11).
- Multi-line Makefile rewrites — only the generator-invocation line
  may change per DEC-3.
- vsetvl* modeled as state-updating SMT instructions inside ordinary
  vector-instruction tests (it is a separate codepath in
  `vsetvl/Tests.scala`).
- Predicate-driven FP operand generation as the primary path per
  DEC-4; `FpValuePred`/`FpTuplePred` survive only for the 2
  `notestfloat3=true` instructions and for audit-side intent
  classification.

## Feasibility Hints and Suggestions

> **Note**: This section is for reference and understanding only. These
> are conceptual suggestions, not prescriptive requirements.

### Conceptual Approach

```
zaozi/rvprobe/src/rvv/
  vtype/
    VType.scala            -- {SEW8/16/32/64} x {LMUL_F8/F4/F2/1/2/4/8}
                              x {VTA/VMA} x {Vill}; legal-combination
                              filter rejecting SEW > XLEN and illegal
                              LMUL/SEW combos
    VTypeEnvelope.scala    -- immutable per-test context: (VType, vl,
                              element-count, register-group occupancy,
                              EEW/EMUL inheritance). Pure data computed
                              before instruction-level solving.

  Schema.scala             -- sealed family of 39 schemas (per Schema
                              inventory table); operand-role list +
                              register-group occupancy + EEW/EMUL
                              inheritance + overlap-legality rules

  pred/
    ValuePred.scala        -- element-level: Zero, One, AllOnes(SEW),
                              MaxSigned(SEW), MinSigned(SEW),
                              MaxUnsigned(SEW), SignBitOnly(SEW),
                              NearMaxSigned(SEW, k),
                              NearMinSigned(SEW, k), Random(SEW, seed),
                              Lit(BigInt, rationale)
    TuplePred.scala        -- tuple-level: ShiftBy0, ShiftBySEWMinus1,
                              DivideByZero, CarryInMask,
                              SaturatingOverflow, IndexInRange,
                              IndexOutOfRange, SegmentNFIELDS(n),
                              NegativeStride, AESKeyImm, ...
    FpValuePred.scala      -- FP element intent classification (used in
                              audit + the 2 notestfloat3 instructions
                              per DEC-4): PosZero, NegZero,
                              QNaN(payload), SNaN(payload), PosInf,
                              NegInf, SmallestSubnormal,
                              LargestSubnormal, SmallestNormal,
                              MaxFinite; FRM as sibling axis
                              (RNE/RTZ/RDN/RUP/RMM)
    FpTuplePred.scala      -- FP tuple intent: NaNPair, MixedSignZeros,
                              DenormalChain

  unittest/
    ElemValueLowering.scala -- PURE function: (TuplePred-set,
                              VTypeEnvelope, [optionally resolved scalar
                              args]) -> witness vector. No Solver calls,
                              no retry, no SMT model variables.
                              Deterministically seeded.
    MagicInstrEmit.scala   -- emits .word 0x<encoded> via existing
                              Statement.Word with exact pspike bit
                              layout: opcode=0x0B, rs1[19:15]=group,
                              rs2[20]=vxsat, rs2[24:21]=LMUL.
    TestSEmit.scala        -- full .S structure: riscv_test.h include,
                              RVTEST_* macros, RVTEST_CODE_BEGIN/END,
                              vsetvli setup, v0/v8/v16/v24 zero-init,
                              per-iteration block, magic word,
                              TEST_CASE(2,x0,0x0), RVTEST_DATA_* with
                              resultdata/testdata sections
    Driver.scala           -- replaces main.go: CLI parity, walks
                              insns/, filters by march/pattern,
                              per-instruction fan-out to stage1 .S +
                              sorted Makefrag. Lives outside the
                              RVGenerator path.

  schemas/                 -- 39 files, one per operand format
  insns/                   -- ~676 declarations, keyed by (extension,
                              name) per AC-14
  notestfloat3/
    Vfredusum.scala        -- Scala source-of-truth case set for
                              vfredusum.vs
    Vfwredusum.scala       -- same for vfwredusum.vs

  vsetvl/
    Tests.scala            -- dedicated codepath: AVL-encoding sweep,
                              immediate sweep, vill triggering per
                              DEC-9. Separate from ordinary insns/
                              fan-out.

  audit/
    TomlIntent.scala       -- offline classifier
    snapshots/<ext>/<name>.json
                           -- per-toml content-hash + classification
                              fixture; basis of AC-15 drift detection
    waivers.md             -- explicit waiver list with
                              owner+reason+date+tracking-ID per AC-13c
    schema-inventory.md    -- generated artifact for AC-18
```

The migration's load-bearing architectural commitment: `ElemValueLowering`
is a pure function. It takes a predicate set and an immutable
`VTypeEnvelope`, and returns the witness vector. It does not invoke the
solver, does not introduce new model variables, and does not loop on
solver feedback. This keeps rvprobe's two-stage SMT contract unchanged
and avoids the architectural risk Codex flagged in round 1.

The second load-bearing commitment: VTYPE is per-test envelope state,
not Recipe-level mutable state. Ordinary vector instructions run under
`vill=false`. The vsetvl* tests live in a separate codepath because
they exercise the CSR-write itself, not value-producing semantics.

### Relevant References

- `zaozi/rvprobe/src/RVGenerator.scala` — existing two-stage solver,
  GAS emission via `.toRecipeAsm()`.
- `zaozi/rvprobe/src/Statement.scala` — `Statement.Word` is the
  primitive `MagicInstrEmit` builds on.
- `zaozi/rvprobe/src/constraints/RVConstraints.scala` — existing
  `vd/vs1/vs2/vm` operand predicates and `isV*` opcode predicates
  (auto-generated; reused).
- `zaozi/rvprobe/src/AsmApi.scala` — existing `vsetvli/vsetvl/vsetivli`
  stubs (used as the starting point for `vsetvl/Tests.scala`).
- `zaozi/rvprobe/src/cases/coverage/CoverageLib.scala` — existing
  per-case generator pattern; the new corpus-walking driver lives
  outside this path per Codex round 1 round-1 OPTIONAL_IMPROVEMENTS.
- `riscv-vector-tests/main.go` — upstream CLI surface to mirror in
  `Driver.scala` per AC-7.
- `riscv-vector-tests/generator/insn.go` — upstream `Insn` struct and
  `Generate()` dispatch table; the 41 `insn_<schema>.go` files are the
  reference for the 39 Scala schemas.
- `riscv-vector-tests/configs/<ext>/*.toml` — the 676 source-of-truth
  TOML files; the audit pass processes these into snapshots and
  vocabulary.
- `riscv-vector-tests/pspike/pspike.cc` — magic-word decoder; the
  rvprobe-side decoder unit test (AC-8) mirrors its bit-layout
  expectations.
- `riscv-vector-tests/Makefile` — generator-invocation line is the
  single edit per DEC-3.
- `riscv-vector-tests/macros/general/test_macros.h` — `TEST_CASE`,
  `RVTEST_*`, `TESTNUM` macros that `TestSEmit.scala` invokes
  (reused, not duplicated).
- `riscv-vector-tests/env/ps/{link.ld,riscv_test.h}` — runtime entry
  point, signature section, exit protocol (reused).

## Dependencies and Sequence

### Milestones

1. **Schema + VTYPE envelope + EEW/EMUL model.** Sealed `Schema` family
   (39 entries, per the Schema inventory table); `VTypeEnvelope` with
   legal-combination filter; EEW/EMUL inheritance for widening,
   narrowing, segmented, and indexed schemas; `schema-inventory.md`
   artifact generated for AC-18.
   - Step A: sealed `Schema` enum mirroring the 39 format strings.
   - Step B: `VType` + `VTypeEnvelope` with legal-combination filter.
   - Step C: EEW/EMUL inheritance rules baked into each schema entry.
   - Step D: artifact emitter writing `audit/schema-inventory.md`.

2. **Predicate vocabulary audit (offline).** Read all 676 tomls,
   classify each literal-tuple into a `TuplePred` (or `Lit` with
   rationale per DEC-1); write `audit/snapshots/`; produce forward and
   backward coverage reports (AC-3, AC-4); port the 2 `notestfloat3`
   case sets to Scala source-of-truth.
   - Step A: `TomlIntent.scala` classifier walking
     `riscv-vector-tests/configs/`.
   - Step B: `TuplePred`/`ValuePred` sealed families finalized from the
     classification output.
   - Step C: `FpValuePred`/`FpTuplePred` populated for audit-side
     intent (note: runtime FP operands remain testfloat3-driven per
     DEC-4).
   - Step D: `notestfloat3/Vfredusum.scala` and
     `notestfloat3/Vfwredusum.scala` carrying the 2 hand-written case
     sets.
   - Step E: forward+backward coverage report; initial
     `audit/waivers.md` for unclassifiable corners.

3. **Deterministic value lowering + magic-word emitter + .S
   structure.** `ElemValueLowering` (pure, no SMT calls);
   `MagicInstrEmit` via `Statement.Word`; `TestSEmit` with full
   RVTEST_* structure; pspike-decoder unit test (AC-8); register-
   occupancy unit tests (AC-17).
   - Step A: `ElemValueLowering` with deterministic seeding.
   - Step B: `MagicInstrEmit` emitting the exact opcode-0x0B
     encoding.
   - Step C: pspike-decoder unit test (rvprobe-side, no spike).
   - Step D: register-occupancy + overlap-legality + fractional-LMUL
     unit-test suite covering 7 LMUL values.
   - Step E: `TestSEmit` producing the `riscv_test.h` + `RVTEST_*` +
     RVTEST_DATA structure.

4. **Driver skeleton + Makefrag emit.** Accepts upstream CLI
   (AC-7), walks `insns/`, filters by march/pattern, emits stage1
   `.S` placeholders + sorted Makefrag (AC-9); TOML-free runtime
   (AC-12).
   - Step A: CLI flag parsing mirroring upstream `main.go`.
   - Step B: `insns/` walker + (extension, name) keying per AC-14.
   - Step C: march filtering + VLEN=64 ZVE branch.
   - Step D: sorted Makefrag emission + per-file naming
     (`<insn>_<ext>-<split>.S`).
   - Step E: Makefile generator-path swap per DEC-3.

5. **Proof-of-concept gate (AC-16).** Nine cases pass end-to-end on
   the release-minimum configuration; per-family cardinality budgets
   measured and pinned. Fan-out to subsequent milestones gated here.
   - Step A: `vadd.vv` at LMUL=1.
   - Step B: `vwadd.vv` at LMUL=4 (register-group occupancy).
   - Step C: `vmseq.vv` (mask-producing).
   - Step D: `vle32.v` + `vse32.v` (unit-stride load/store).
   - Step E: `vluxei32.v` (indexed, EEW separation).
   - Step F: `vsseg2e32.v` (segmented store, NFIELDS x EMUL <= 8).
   - Step G: `vnclip.wv` (saturating with vxrm, vxsat magic
     encoding).
   - Step H: `vfadd.vv` x 5 FRM modes (testfloat3-driven per DEC-4).
   - Step I: vsetvli sweep.
   - Step J: cardinality measurement + budget pinning.

6. **Integer schema fan-out.** Remaining 23 integer schemas (per
   the Schema inventory's Integer row); each gets predicate-set
   wiring + smoke test.

7. **vsetvl* dedicated codepath.** `vsetvl/Tests.scala`:
   AVL-encoding sweep, immediate sweep, vill triggering per DEC-9
   sub-decisions.

8. **FP schemas + testfloat3 wiring.** 5 FP schemas per the Schema
   inventory; FRM sweep across 5 modes; `repeat` semantics
   (FP-only); `notestfloat3` instruction case sets exercised from
   the Scala sources committed in Milestone 2; testfloat3 invoked
   as subprocess per DEC-4.

9. **Load/store family.** 8 load/store schemas: unit-stride,
   strided (positive and negative), indexed (ordered and
   unordered), segmented (NFIELDS x EMUL <= 8). EEW/EMUL inheritance
   per Milestone 1's rules.

10. **Sub-extensions.** zvbb (16 insns), zvbc (4), zvfh, zvkned
    (11 AES, EGW alignment), zvknha (SHA-256), zvksed (SM4),
    zvksh (SM3), zvkg (GHASH/GMUL), zvfbfmin, zvfbfwma. Most reuse
    existing schemas.

11. **CSR-axis coverage.** vxrm (22 insns x 4 modes), vxsat
    (14 insns x 2 states), FRM (FP x 5 modes); satisfies AC-10.

12. **Full-pipeline soak.** Pipeline pass on every DEC-6 release-
    blocking matrix tuple per AC-6. Re-verifies AC-18 (schema
    inventory artifact still matches `Schema.scala`).

13. **Audit closeout.** AC-3, AC-4, AC-13a, AC-13b, AC-13c, AC-15,
    AC-17 all passing. `riscv-vector-tests/` retention decision per
    DEC-7 is acted on.

### Dependencies

- Milestones 1, 2, and 3 are largely independent and can interleave
  once Milestone 1's `Schema` enum is frozen (Milestone 2's audit
  classifier needs schema context).
- Milestone 4 depends on Milestones 1 and 3 (CLI walks `insns/`;
  `insns/` declarations reference `Schema`; emission uses `TestSEmit`).
- Milestone 5 (POC gate) depends on Milestones 1–4 completing
  end-to-end on the 9 chosen cases.
- Milestones 6, 7, 8, 9, 10 are largely parallel-able once Milestone 5
  passes; each fans out a different schema family.
- Milestone 11 depends on Milestones 6 and 8 (vxrm/vxsat in integer;
  FRM in FP).
- Milestone 12 depends on all schema-implementing milestones (6–11).
- Milestone 13 depends on Milestones 2 (snapshots) + 12 (pipeline
  soak) + AC-17 (Milestone 3).

## Task Breakdown

Each task carries exactly one routing tag (`coding` or `analyze`).

| Task ID | Description | Target AC | Tag | Depends On |
|---------|-------------|-----------|-----|------------|
| task1  | Implement sealed `Schema` family (39 entries) + `VType` + `VTypeEnvelope` + legal-combination filter + EEW/EMUL inheritance; emit `audit/schema-inventory.md` artifact | AC-1, AC-5, AC-18 | coding | - |
| task2  | Implement `TomlIntent.scala` offline classifier; walk all 676 upstream tomls; classify every literal-tuple into `TuplePred`/`Lit`; produce forward+backward coverage report; write `audit/snapshots/` and initial `audit/waivers.md` | AC-3, AC-4 | analyze | task1 |
| task3  | Port `vfredusum.vs` and `vfwredusum.vs` case sets to Scala source-of-truth under `notestfloat3/` | AC-10 | coding | task1 |
| task4  | Implement `ElemValueLowering` as a pure function with deterministic seeding; no Solver/SMT calls; no model variables; no retry loop | AC-12 | coding | task1, task2 |
| task5  | Implement `MagicInstrEmit` using `Statement.Word` with exact pspike bit layout (opcode=0x0B, rs1[19:15]=group, rs2[20]=vxsat, rs2[24:21]=LMUL); add rvprobe-side pspike-decoder unit test | AC-8 | coding | task1 |
| task6  | Implement `TestSEmit` with full RVTEST_* structure (riscv_test.h include, RVTEST_CODE_BEGIN/END, vsetvli setup, v0/v8/v16/v24 zero-init, magic word, TEST_CASE(2,x0,0x0), RVTEST_DATA section) | AC-11 | coding | task5 |
| task7  | Build register-occupancy + overlap-legality + fractional-LMUL Scala unit-test suite covering all 7 LMUL values (F8/F4/F2/1/2/4/8) | AC-17 | coding | task1 |
| task8  | Implement `Driver.scala`: CLI parity with upstream `main.go`; walk `insns/`; march filter; VLEN=64 ZVE branch; sorted Makefrag emission; dot-to-underscore naming; (extension, name) keying | AC-7, AC-9, AC-12, AC-14 | coding | task6 |
| task9  | Generator-path swap in upstream `Makefile` per DEC-3 (single line change) | AC-11 | coding | task8 |
| task10 | Implement the 9 POC cases end-to-end (vadd.vv, vwadd.vv@LMUL=4, vmseq.vv, vle32.v+vse32.v, vluxei32.v, vsseg2e32.v, vnclip.wv, vfadd.vv x 5 FRM, vsetvli); measure and pin per-family cardinality budgets | AC-16, AC-13b | coding | task4, task7, task9 |
| task11 | Implement remaining 23 integer schemas + smoke tests per the Schema inventory's Integer row | AC-1, AC-13b | coding | task10 |
| task12 | Implement `vsetvl/Tests.scala` separate codepath: AVL-encoding sweep, immediate sweep, vill triggering per DEC-9 sub-decisions | AC-5 | coding | task1 |
| task13 | Implement 5 FP schemas + testfloat3 subprocess wiring + 5 FRM modes per DEC-4; integrate `notestfloat3/` sources from task3 | AC-10 | coding | task10 |
| task14 | Implement 8 load/store schemas: unit-stride, strided (positive + negative), indexed (ordered + unordered), segmented (NFIELDS x EMUL <= 8) | AC-1 | coding | task10 |
| task15 | Implement sub-extensions (zvbb, zvbc, zvfh, zvkned, zvknha, zvksed, zvksh, zvkg, zvfbfmin, zvfbfwma); declarations under `insns/<ext>/`; reuse existing schemas where possible | AC-2 | coding | task11, task14 |
| task16 | Wire CSR-axis coverage: vxrm (22 insns x 4 modes), vxsat (14 insns x 2 states), FRM (FP x 5 modes) | AC-10 | coding | task11, task13 |
| task17 | Implement TOML-drift CI check; snapshot refresh workflow with pinned-upstream-commit bump + audit regen + human-reviewed diff (three-commit pattern) | AC-15 | coding | task2 |
| task18 | Full-pipeline soak across DEC-6 release-blocking matrix: (VLEN=256, XLEN=64, full march) and (VLEN=128, XLEN=64, full march); re-verify AC-18 | AC-6, AC-18 | coding | task15, task16 |
| task19 | Closeout audit: verify AC-3, AC-4, AC-13a/b/c, AC-15, AC-17 all passing; act on DEC-7 retention decision | AC-3, AC-4, AC-13a, AC-13b, AC-13c | analyze | task17, task18 |

## Claude-Codex Deliberation

### Agreements
- Spike, pspike, testfloat3, macros, env, single, and the Makefile are
  the right compatibility anchors; the migration touches none of them
  (except a single-line generator-path swap in `Makefile` per DEC-3).
- Schema-first instruction declarations are the right structural
  organization; the 39-entry sealed family is the source of truth.
- Duplicate-name disambiguation by (extension, name) must be mandatory
  to avoid the upstream silent last-wins bug.
- The "one representative schema end-to-end" milestone (formalized as
  AC-16's 9-case POC gate) is necessary before broad fan-out.
- The two-stage SMT solver contract (opcode IDs in stage 1, scalar args
  in stage 2) stays unchanged.

### Resolved Disagreements

- **ElemValueLowering placement** (Codex round 1, DISAGREE):
  Codex argued that vector-element witnesses do not fit rvprobe's
  current two-stage SMT model. Claude initial position embedded
  element generation inside the solver. **Resolution**:
  `ElemValueLowering` is a pure function outside the SMT solver — no
  Solver calls, no retry loop, no new model variables. The solver's
  contract is unchanged; vector data is deterministically lowered from
  the predicate set + VTypeEnvelope.

- **VTYPE state representation** (Codex round 1, DISAGREE):
  Codex argued that mutable Recipe-level VTYPE state cannot survive
  the stage-1/stage-2 replay. Claude initial position used mutable
  state. **Resolution**: VTYPE is per-test immutable `VTypeEnvelope`,
  computed before instruction-level solving begins; vsetvl* tests
  live in a separate codepath that exercises the CSR write itself.

- **vsetvl* test placement** (Codex round 1, DISAGREE):
  Initial design tried to model `vsetvl*` as a state-updating SMT
  instruction inside ordinary instruction tests. **Resolution**:
  ordinary tests run under `vill=false` with a fixed envelope;
  `vsetvl*` tests are a dedicated separate codepath at
  `zaozi/rvprobe/src/rvv/vsetvl/Tests.scala`.

- **Phase ordering** (Codex round 1, DISAGREE):
  Initial plan ran predicate-vocabulary audit before schema +
  VTYPE model. **Resolution**: inverted — Milestone 1 establishes
  Schema + VTYPE + EEW/EMUL; Milestone 2 runs the audit pass with
  schema context available.

- **Cardinality drift framing** (Codex round 1, DISAGREE, then
  round 2 OPTIONAL_IMPROVEMENT):
  Initial AC-13 promised ±50% cardinality bound. **Resolution**:
  replaced with coverage-based AC-13a (tuple coverage), AC-13b
  (emitted-test coverage), AC-13c (waiver governance). Per-family
  budgets are measured during AC-16's POC gate and become hard upper
  bounds for subsequent milestones.

- **Predicate vocabulary tuple-level vs value-level** (Codex round 1
  CORE_RISK):
  Upstream TOMLs are operand tuples, not independent value bags.
  Initial draft framed predicates at element level only.
  **Resolution**: `TuplePred` is the primary predicate category;
  `ValuePred` provides the element-level building blocks; tuple-level
  predicates capture curator intent like ShiftBy0, DivideByZero,
  CarryInMask, NaNPair, SegmentNFIELDS(n).

- **FP operand generation path** (Codex round 1
  MISSING_REQUIREMENTS):
  Initial draft was ambiguous about FP operand source. User
  decision DEC-4 resolves: testfloat3 continues to supply FP operands
  at runtime; `FpValuePred`/`FpTuplePred` survive only for audit-side
  intent classification and the 2 `notestfloat3=true` instructions.

- **`Lit(BigInt)` policy** (Codex preferred audit-debt;
  Claude preferred permanent):
  User decision DEC-1 resolves: permanent with rationale required.
  Each `Lit` entry has a one-line rationale and is not required to
  eventually be reclassified.

- **Makefile modification policy** (open in early rounds):
  User decision DEC-3 resolves: generator-path swap only (single-line
  edit to the Makefile invocation of the generator). All other
  Makefile content stays.

- **Release-blocking matrix** (open in early rounds):
  User decision DEC-6 resolves: (VLEN=256, XLEN=64, full march) and
  (VLEN=128, XLEN=64, full march). VLEN=64 / XLEN=32 / integer-only
  builds are NOT release-blocking.

- **AC-3 vs AC-13a contradiction** (Codex round 3, REQUIRED_CHANGE):
  AC-3 originally said "every tuple classified" while AC-13a allowed
  waivers. **Resolution**: AC-3 reworded to "classified OR waived";
  classified and waived are non-overlapping non-failing outcomes;
  unclassified-and-unwaived count = 0.

- **AC-10 notestfloat3 FRM sweep** (Codex round 3, REQUIRED_CHANGE):
  Original wording was ambiguous about whether the 2 notestfloat3
  instructions sweep FRM. **Resolution**: yes, all 5 FRM modes; the
  difference is operand source (Scala vs testfloat3), not coverage.

- **Fractional-LMUL coverage** (Codex round 3, REQUIRED_CHANGE):
  Original AC-16 only stressed LMUL=4. **Resolution**: AC-17 now
  requires structural Scala unit-test coverage of all 7 LMUL values
  (F8/F4/F2/1/2/4/8), independent of spike.

### Convergence Status
- Final Status: `converged`
- Rounds executed: 4 (round 1 v1, round 2 v2, round 3 v3, round 4
  v3-patched). Codex round 4 returned `CONVERGENCE: YES` with no
  remaining `REQUIRED_CHANGES`.

## Pending User Decisions

The DECs below remain open. DEC-1, DEC-3, DEC-4, and DEC-6 were
resolved during plan generation and are recorded under Resolved
Disagreements above.

- **DEC-5: `-configs` flag handling.**
  - Claude Position: silently ignore (no toml dir exists in rvprobe).
  - Codex Position: N/A — open question.
  - Tradeoff Summary: `-configs` upstream points at the configs/
    directory of `.toml` files. After migration there is no configs/.
    Options: (a) silently ignore the flag (lowest disruption), (b)
    repurpose for a predicate-override file path, (c) error out so
    callers know the contract changed.
  - Decision Status: `PENDING`

- **DEC-7: `riscv-vector-tests/` retention.**
  - Claude Position: retain as a pinned audit fixture for one release
    cycle (the tomls remain the source of truth for what intents we
    needed to cover); delete after the rvprobe driver is exercised in
    CI and the audit closeout completes.
  - Codex Position: N/A — open question.
  - Tradeoff Summary: delete-immediately reduces workspace clutter but
    loses the predicate-audit reference; retain-one-release keeps a
    safety net; retain-permanently turns the upstream into a permanent
    dependency.
  - Decision Status: `PENDING`

- **DEC-8: Duplicate-name keying policy.**
  - Claude Position: always key by (extension, name), even when
    no collision exists; keeps the directory layout predictable.
  - Codex Position: N/A — open question.
  - Tradeoff Summary: always-(extension,name) is uniform but causes
    deeper paths for non-colliding insns; only-on-collision keeps the
    common case flat but introduces an asymmetric special case.
  - Decision Status: `PENDING`

- **DEC-9: `vill=1` test interpretation** (three sub-decisions).
  - DEC-9a (CSR-state assertion):
    - Claude Position: yes — every illegal vsetvl* test verifies
      `vill` is set and `vl=0`.
    - Codex Position: N/A — open question.
    - Decision Status: `PENDING`
  - DEC-9b (Trap behavior on vsetvl* itself):
    - Claude Position: no trap on the vsetvl* itself (CSR-only path);
      matches upstream behavior.
    - Codex Position: N/A — open question.
    - Decision Status: `PENDING`
  - DEC-9c (Illegal post-vill execution): conditional on DEC-9b.
    - Claude Position: if DEC-9b chooses "no trap on vsetvl*", do NOT
      additionally attempt to execute a vector instruction under
      `vill=1` — stop after the CSR check. (If DEC-9b chooses "trap on
      vsetvl*", DEC-9c is moot.)
    - Codex Position: N/A — open question.
    - Decision Status: `PENDING`

- **DEC-10: Future upstream TOML sync.**
  - Claude Position: DEC-10a (manual sync only) — human owner bumps
    pinned upstream commit, regenerates audit, reviews vocabulary
    diff, commits all three together. Lowest tooling complexity;
    aligns with AC-15's three-commit workflow.
  - Codex Position: N/A — open question.
  - Tradeoff Summary:
    - DEC-10a: manual sync only. Owner: TBD. Lowest complexity, but
      sync may lag.
    - DEC-10b: automated PR-bot regenerates the audit on upstream
      change and proposes vocabulary additions or removals; human
      merges. Higher tooling investment, faster sync.
    - DEC-10c: fork the curator role entirely; rvprobe predicate
      vocabulary evolves independently. Highest autonomy, loses
      upstream curator wisdom.
  - Decision Status: `PENDING`

## Implementation Notes

### Code Style Requirements
- Implementation code and comments must NOT contain plan-specific terminology such as "AC-", "Milestone", "Step", "Phase", or similar workflow markers
- These terms are for plan documentation only, not for the resulting codebase
- Use descriptive, domain-appropriate naming in code instead

## Output File Convention

This template is used to produce the main output file (e.g., `plan.md`).

### Translated Language Variant

When `alternative_plan_language` resolves to a supported language name through merged config loading, a translated variant of the output file is also written after the main file. Humanize loads config from merged layers in this order: default config, optional user config, then optional project config; `alternative_plan_language` may be set at any of those layers. The variant filename is constructed by inserting `_<code>` (the ISO 639-1 code from the built-in mapping table) immediately before the file extension:

- `plan.md` becomes `plan_<code>.md` (e.g. `plan_zh.md` for Chinese, `plan_ko.md` for Korean)
- `docs/my-plan.md` becomes `docs/my-plan_<code>.md`
- `output` (no extension) becomes `output_<code>`

The translated variant file contains a full translation of the main plan file's current content in the configured language. All identifiers (`AC-*`, task IDs, file paths, API names, command flags) remain unchanged, as they are language-neutral.

When `alternative_plan_language` is empty, absent, set to `"English"`, or set to an unsupported language, no translated variant is written. Humanize does not auto-create `.humanize/config.json` when no project config file is present.

--- Original Design Draft Start ---

# Migrate riscv-vector-tests onto RVProbe

## Goal

Re-implement the per-instruction RVV unit-test generator currently shipped as
`riscv-vector-tests/` (Go, ksco) on top of the RVProbe Scala eDSL living under
`zaozi/rvprobe/` (currently on branch `dev`).

The migration is **semantic, not literal**: the curated hex value tables in
`configs/<ext>/*.toml` upstream are NOT carried over as data. Instead, the
*intent* behind those curated values — sign-bit corners, SEW boundaries,
all-ones, all-zeros, near-overflow, etc. — is re-expressed as a vocabulary of
RVProbe predicates / SMT constraints, and each instruction declares which
predicates it samples from. The TOMLs are dropped after migration; the
predicate vocabulary becomes the new test contract.

The downstream toolchain (spike + pspike co-sim oracle, testfloat3, Makefrag,
`env/`, `macros/general/`, `single/`) is reused as-is. The rvprobe side
generates `.S` + `Makefrag`; spike + pspike compute the golden signature
exactly as today.

After migration, `riscv-vector-tests/` (Go) can be removed from the workspace;
the rvprobe-side generator + predicate vocabulary becomes the single source of
truth for RVV unit-test inputs.

## Why migrate

1. `riscv-vector-tests/` is a procedural Go generator with no coverage
   guarantees (README explicitly states "no coverage guarantees"). Each
   instruction template is hand-rolled in a separate Go file under
   `generator/insn_*.go`; consistency across schemas relies on convention.
2. RVProbe already provides typed, SMT-backed constraint composition
   (`vd / vs1 / vs2 / vm` operands, OM-derived predicates, coverage-aware
   sequence-level APIs like `coverWAR`). Re-expressing the same instruction
   space inside RVProbe lets future RVV work share one constraint vocabulary
   with the scalar work already in the paper, and lets us add chaining-matrix
   coverage to the same test corpus instead of bolting it on later.
3. Migration is a forcing function: every operand schema that exists in the Go
   generator must show up in the rvprobe eDSL, which surfaces gaps in the RVV
   side of the framework that we currently cannot see.

## What "migration" means here (in-scope / out-of-scope)

**In scope:**
- Every instruction currently covered by `configs/<ext>/<insn>.toml` upstream
  (V + zvbb + zvbc + zvfbfmin + zvfbfwma + zvfhmin + zvkg + zvkned + zvknha +
  zvksed + zvksh, ~700+ instruction variants total) must be declared in the
  rvprobe RVV instruction table and reachable from the driver.
- Every operand-schema family currently in `generator/insn_*.go` (41 files —
  `insn_vdvs2vs1vm.go`, `insn_vdvs2imm.go`, `insn_vdrs1mvs2vm.go`,
  `insn_vsetvli.go`, `insn_vsetvl.go`, `insn_vsetivli.go`, the load/store
  `insn_vs3rs1m*.go` family, etc.) must have a typed counterpart in the
  rvprobe Scala API.
- A **predicate vocabulary** (`ValuePred`) covers every "value intent" the
  upstream curators expressed. The intent set (derived by reading all
  ~700 tomls) must include at minimum: `Zero`, `One`, `AllOnes(SEW)`,
  `MaxSigned(SEW)`, `MinSigned(SEW)`, `MaxUnsigned(SEW)`, `SignBitOnly(SEW)`,
  `NearMaxSigned(SEW, k)`, `NearMinSigned(SEW, k)`, `Random(SEW, seed)`.
  The vocabulary is extensible — additional predicates are added as the
  audit pass surfaces them.
- Each rvprobe instruction declaration lists the predicate set it samples
  from, optionally constrained by SEW/LMUL. The SMT layer solves for one
  concrete value per (predicate × SEW) cell at generation time.
- The CLI surface of `main.go` must be reachable from rvprobe's runner:
  `-VLEN`, `-XLEN`, `-split`, `-integer`, `-pattern`, `-stage1output`,
  `-configs` (renamed / repurposed — see Pending decisions), `-testfloat3level`,
  `-repeat`, `-march`.
- Output: per-instruction `.S` files + a `Makefrag` listing them, in the
  exact location/shape pspike + the makefile chain expect.

**Out of scope (stays as-is, no rewrite):**
- `pspike/pspike.cc`: spike co-sim oracle. rvprobe does NOT produce reference
  results; it only produces test inputs (the `.S`) and the driver to invoke
  them. spike + the existing pspike-injected special instruction stay the
  source of golden results.
- `testfloat3/`: external floating-point reference. The rvprobe side calls
  into it the same way the Go side does, via the same .S-level glue.
- `macros/general/`: hand-written assembly macros for trap handlers, signature
  emit, etc. Reused verbatim.
- `env/`: linker scripts, startup, runtime. Reused verbatim.
- `single/`: the per-test wrapper. Reused verbatim.
- `Makefile` / `Makefrag` build orchestration: rvprobe's output target must
  match the contract the Makefile expects; the Makefile itself isn't
  rewritten.

**Explicitly NOT promised:**
- **Byte-equivalent `.S` with upstream.** Because rvprobe synthesizes
  concrete values from predicates via SMT, the literal hex initializers in
  the emitted `.S` will not match upstream byte-for-byte. The test contract
  is at the predicate-coverage level, not the literal-value level. Validation
  is via spike + pspike self-consistency, not via diffing against upstream.
- **Identical test count.** A predicate may resolve to fewer or more concrete
  samples per (insn, SEW) than the upstream toml listed; the count is a
  function of the predicate set, not the curator's hand-picked list size.

## Architecture sketch

### Upstream Go structure (current state)

```
main.go            -> walk configs, dispatch by filename + march, fan out
generator/insn.go  -> Insn struct, toml parsing, Generate() dispatch table
generator/insn_<schema>.go  (41 files) -> one per operand schema
configs/<ext>/<insn>.toml   (~700 files) -> per-instruction value tables
pspike/pspike.cc   -> spike modification for golden output
```

The schema is **encoded in the Go filename**: `insn_vdvs2vs1vm.go` means
"this generator emits instructions with operand slots vd, vs2, vs1, vm." The
.toml `format` field is a literal of the schema (`"vd,vs2,vs1,vm"`) and is
how `Insn.Generate()` chooses which `insn_<schema>` codepath to run.

### Target rvprobe structure (post-migration)

```
zaozi/rvprobe/src/rvv/
  Schema.scala        -- typed encoding of operand schemas (vd/vs2/vs1/vm,
                         vd/vs2/imm, vd/rs1/m/vs2/vm, ...) as a sealed family
  ValuePred.scala     -- the predicate vocabulary: Zero, One, AllOnes(SEW),
                         MaxSigned(SEW), MinSigned(SEW), MaxUnsigned(SEW),
                         SignBitOnly(SEW), NearMaxSigned(SEW, k),
                         NearMinSigned(SEW, k), Random(SEW, seed), and an
                         escape hatch Lit(BigInt) for one-off corners the
                         vocabulary can't yet name. Each predicate lowers to
                         an SMT constraint on the operand bitvector.
  Op.scala            -- per-instruction declarations: name, schema,
                         predicate-set per operand (optionally SEW-keyed),
                         eligibility (which SEW / Fp / Bfloat16 / Float16
                         it accepts). Replaces the upstream .toml entirely.
  Driver.scala        -- replaces main.go: CLI parse, march filter, pattern
                         filter, fan-out, write .S + Makefrag
  schemas/            -- one Scala file per operand schema, each implements
                         the SMT-driven body that emits the .S text for that
                         schema (replaces the 41 generator/insn_*.go files)
  insns/              -- one Scala declaration per instruction (vadd_vv.scala
                         etc.) — usually 3–10 lines: name, schema reference,
                         predicate set. Replaces all ~700 upstream tomls.
```

The chaining-matrix work already on `dev` is orthogonal: it lives at the
sequence level (D × C cells) and consumes single instructions as building
blocks. After this migration the matrix can be re-pointed at the rvprobe
RVV instruction declarations instead of the scalar set.

### Operand-schema mapping (representative, not exhaustive)

| Go file                         | rvprobe schema                  | example insns           |
|---|---|---|
| `insn_vdvs2vs1vm.go`           | `VdVs2Vs1Vm`                    | `vadd.vv`, `vand.vv`    |
| `insn_vdvs2rs1vm.go`           | `VdVs2Rs1Vm`                    | `vadd.vx`               |
| `insn_vdvs2immvm.go`           | `VdVs2ImmVm`                    | `vadd.vi`               |
| `insn_vs3rs1mvs2vm.go`         | `Vs3Rs1MVs2Vm` (indexed store)  | `vsxei8.v`              |
| `insn_vsetvli.go` (special)    | `VsetVli` (separate codepath)   | `vsetvli`               |
| `insn_vsetvl.go` (special)     | `VsetVl`                        | `vsetvl`                |
| `insn_vsetivli.go` (special)   | `VsetIVli`                      | `vsetivli`              |
| `insn_g.go`                    | `Misc` / per-insn one-offs      | irregular shapes        |

The 41 Go files collapse into ~10–15 Scala schemas plus 3 vsetvl one-offs.
Several Go files are near-duplicates differing only in mask/v0 presence;
RVProbe's existing parametricity makes these collapse natural.

### What the rvprobe side actually emits

For `vadd.vv`, the upstream output is a `.S` file with one block per
(SEW, LMUL, value-pair) combination, each block writing inputs into a
register group, executing `vadd.vv vd, vs2, vs1`, and storing the result for
pspike to diff.

Post-migration, rvprobe emits the same *shape* (.S with the same macro
invocations, the same SEW/LMUL sweep, blocks of the same general structure),
but the concrete value-pair fed into each block is the SMT-solved witness of
the predicate set declared on the instruction — not the literal from a toml.
For `vadd.vv` whose predicates are e.g. `{Zero, MaxSigned, MinSigned,
NearMaxSigned(1), AllOnes, Random(seed=...)}` × {sew8, sew16, sew32, sew64},
the emitter produces one block per (predicate, SEW) cell, with the concrete
hex initializer chosen by the solver. The .S is consumed by spike + pspike
exactly as before; pspike sees a valid RVV instruction with valid operand
inputs and produces a signature. The signature is the unit of correctness,
not the literal hex value.

## Pending decisions (questions for gen-plan / RLCR loop)

1. **Predicate vocabulary completeness.** The audit pass over the ~700
   upstream tomls produces a candidate set of intents. Some upstream literals
   may not cleanly classify into a named predicate (e.g., values that look
   random but were actually picked to hit a specific microarchitectural
   pattern). Decision needed: (a) every upstream literal must be classified —
   add `Lit(BigInt)` entries to the vocabulary for un-classifiable corners,
   keeping them named but escape-hatched; (b) discard unclassifiable
   literals on the theory that they were arbitrary anyway. Default: (a),
   because losing curator wisdom silently is the worst failure mode.

2. **Predicate sampling cardinality.** A predicate like `Random(SEW, seed)`
   needs a sample count. Upstream emits exactly the count the curator wrote
   into the toml; rvprobe needs an explicit rule. Decision needed:
   (a) per-instruction sample-count override on each `Random` predicate
   (mirrors upstream cardinality), or (b) global sample count from a CLI
   flag (e.g., `-samples N`). Default: (a) for the initial port so cardinality
   stays close to upstream; (b) added later as a coverage knob.

3. **`-pattern` regex filter semantics.** Upstream uses Go `regexp` syntax.
   rvprobe-side must keep the same regex behavior so test corpora produced
   from the same CLI invocation are identical. Decision: use a regex engine
   with the same feature set (java.util.regex covers everything upstream
   uses except possessive quantifiers, which upstream doesn't use).

4. **testfloat3 integration.** Upstream embeds testfloat3 references at .S
   emit time (via the FP repeat path). Question: does the rvprobe side
   shell out to the same testfloat3 binary, or does it pre-compute the
   reference once and emit it as a `.S` literal? Default: shell out (no
   reference values stored in the rvprobe tree).

5. **Drop-in CLI vs new CLI shape.** Upstream main.go takes `-VLEN`, `-XLEN`,
   `-split`, `-integer`, `-pattern`, `-stage1output`, `-configs`,
   `-testfloat3level`, `-repeat`, `-march` as Go-`flag` style (`-foo value`).
   rvprobe's existing CLIs may already use a scopt-style API. Decision: the
   rvprobe RVV driver accepts the upstream flag names verbatim so callers
   in the existing Makefile do not have to change. Note: `-configs` loses
   its original meaning (there is no config directory anymore); decide
   whether to (a) silently ignore it, (b) repurpose it for a predicate
   override file, or (c) error out.

6. **Coverage layer on top.** Upstream has no coverage instrumentation. Post-
   migration, do we (a) emit the same test corpus with no coverage layer
   (pure port), or (b) also attach the chaining matrix / sequence-level
   coverage that already exists on the `dev` branch? Default: (a) for the
   initial port; (b) becomes a follow-up.

7. **Removal of `riscv-vector-tests/` after migration.** Once the rvprobe
   driver produces a semantically-equivalent `.S` + `Makefrag` set (in the
   sense that spike + pspike accept them and produce valid signatures),
   do we delete the upstream Go tree, or keep it as a reference? Default:
   keep it for one release as a predicate-audit reference (the tomls are
   the source of truth for what intents we needed to cover); delete after
   the rvprobe driver is exercised in CI and the predicate audit is
   complete.

8. **vsetvl* generators are special-cased upstream** with their own Go files
   (`insn_vsetvl.go`, `insn_vsetvli.go`, `insn_vsetivli.go`) because their
   operand encoding does not fit the generic schema. Confirm the rvprobe
   side treats them the same way (separate codepath, not forced into the
   common `VdVs2Vs1Vm`-style schema).

## Acceptance criteria sketches

- **AC: schema coverage.** Every operand schema present in `generator/insn_*.go`
  upstream has at least one rvprobe Scala counterpart, and the mapping is
  documented (a table — Go filename → rvprobe schema name).
- **AC: instruction coverage.** Every `configs/<ext>/*.toml` upstream
  corresponds to exactly one declaration under `zaozi/rvprobe/src/rvv/insns/`.
  Test: walk both sets and confirm symmetric-difference is empty.
- **AC: predicate intent coverage (forward).** Every hex literal that appears
  in any upstream `.toml` `base` / `sew*` array is classifiable into at least
  one predicate in `ValuePred` (using `Lit(BigInt)` as the escape hatch).
  Test: audit script that reads all tomls and emits a per-literal
  classification report; the unclassified count must be 0.
- **AC: predicate intent coverage (backward).** Every predicate in `ValuePred`
  (other than `Lit` and `Random`) is justified by at least one upstream
  literal that was classified into it. Test: same audit script, reversed —
  predicates with zero supporting literals are flagged as dead vocabulary.
- **AC: spike + pspike self-consistency.** The full Makefile pipeline
  (rvprobe driver → `.S` → spike + pspike → signature) passes on the same
  march string the upstream README documents
  (`-march=gcv_zvbb_zvbc_zfh_zvfh_zvkg_zvkned_zvknha_zvksed_zvksh_zfbfmin_zvfbfmin_zvfbfwma`).
  pspike's diff between spike's signature and the .S-embedded expected
  signature is empty for every emitted test. (This replaces "byte-equivalent
  with upstream" — the rvprobe pipeline is its own oracle-consistent
  generator/oracle pair.)
- **AC: CLI compatibility.** The rvprobe driver accepts `-VLEN`, `-XLEN`,
  `-split`, `-integer`, `-pattern`, `-stage1output`, `-testfloat3level`,
  `-repeat`, `-march` with identical semantics and identical defaults.
  `-configs` is handled per Pending decision #5.
- **AC: vsetvl path.** `vsetvl`, `vsetvli`, `vsetivli` are handled by
  dedicated codepaths in the rvprobe side; they are not forced into the
  generic schema.
- **AC: no spike rewrite.** No file under `pspike/`, `testfloat3/`,
  `macros/`, `env/`, or `single/` is modified by this migration.
- **AC: TOML-free post-condition.** After migration completes and the
  upstream tree is removed, no `.toml` file remains under
  `zaozi/rvprobe/src/rvv/`. Test: `find zaozi/rvprobe/src/rvv -name "*.toml" | wc -l` returns 0.

## Anti-goals (explicitly NOT this work)

- Re-implementing spike, pspike, or the testfloat3 oracle.
- Adding a "self-verifying" mode where rvprobe pre-computes expected values
  (explicitly rejected — spike remains the oracle).
- Promising byte-equivalent `.S` with upstream. The migration is semantic,
  not literal; the predicate vocabulary IS the contract.
- Carrying any `.toml` files into the rvprobe tree. The tomls are read once
  during the audit phase to extract predicate intent and are then dropped.
- Adding new RVV extensions not currently covered upstream (zvfbfmin /
  zvksh / … are already in scope via upstream tomls; truly new extensions
  are follow-ups).
- Re-organizing the existing chaining-matrix work on the `dev` branch.
- Touching the paper / iccd26 tree (`paper/`).

## Suggested phase breakdown (not binding — gen-plan/RLCR decides final order)

1. **Predicate-vocabulary audit.** Read all ~700 upstream tomls; for every
   hex literal in every `base` / `sew*` array, classify into a candidate
   predicate (`Zero`, `MaxSigned(SEW)`, `SignBitOnly(SEW)`, etc.) or mark as
   unclassifiable. Output: a draft `ValuePred` sealed family + a coverage
   report (literals per predicate, unclassified count). This phase determines
   the actual predicate vocabulary; subsequent phases consume it.
2. **Driver skeleton.** rvprobe driver compiles, accepts the upstream CLI,
   walks the rvprobe-side instruction table, emits empty `.S` placeholders.
   Verifies the IO contract before any schema is wired.
3. **One representative schema end-to-end.** Pick `VdVs2Vs1Vm` (because
   `vadd.vv` is the most common upstream shape). Wire `vadd.vv` with its
   declared predicate set, emit `.S`, run through spike + pspike, confirm
   signature self-consistent.
4. **Schema fan-out.** Implement the remaining ~14 schemas. Each schema gets
   one smoke-test insn and a predicate-coverage check.
5. **vsetvl special cases**: `vsetvl`, `vsetvli`, `vsetivli`.
6. **Sub-extensions**: zvbb, zvbc, zvfh, zvkned, zvknha, zvksed, zvksh, zvkg,
   zvfbfmin, zvfbfwma, zvfhmin — most fall under existing schemas, so this is
   mostly instruction-declaration wiring, not new schemas.
7. **CLI parity audit + Makefrag emit + full-pipeline smoke** through the
   upstream Makefile.
8. **Audit closeout.** Forward + backward predicate-coverage AC must pass.
   `riscv-vector-tests/` can then be removed per Pending decision #7.

## References (in-workspace)

- Upstream Go generator: `riscv-vector-tests/` (~3.7k LOC across 41 generator
  files + ~700 configs).
- rvprobe Scala eDSL: `zaozi/rvprobe/` on branch `dev` (commit `e9a6a58`).
- Paper context: `paper/iccd26/rvprobe.tex` — the abstraction-gap thesis
  and the chaining-matrix work that this migration is upstream of.

--- Original Design Draft End ---
