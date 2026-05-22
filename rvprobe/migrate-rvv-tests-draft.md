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
