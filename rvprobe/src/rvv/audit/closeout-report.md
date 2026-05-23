# task19 — Audit Closeout Report

Generated at the conclusion of the migration's structural-foundation
phase (tasks 1-18). Records the final state of every plan acceptance
criterion as of this checkpoint, before any downstream spike-pipeline
soak.

## Acceptance Criteria — Final Status

| AC | Status | Evidence |
|----|--------|----------|
| AC-1  | CLOSED | `Schema.scala` 39 entries + `byFormatString` / `lookup` (strict-error) + audit `schema-inventory.md` + `SchemaTest` (11 tests, including upstream-format-subset check passing) |
| AC-2  | CLOSED | 676 RvvInsn declarations under `insns/<Ext>.scala` (auto-generated from snapshots); per-extension counts match upstream exactly (v=629, zvbb=16, zvbc=4, zvfbfmin=2, zvfbfwma=2, zvfhmin=2, zvkg=2, zvkned=11, zvknha=3, zvksed=3, zvksh=2 = 676) |
| AC-3  | CLOSED | Every upstream literal-tuple classifies via `TomlIntent` (Lit fallback acceptable per DEC-1); 0 unclassified-and-unwaived across 14573 rows total; `forward-report.md` records `litOnlyCount = 775` informational metric |
| AC-4  | CLOSED | 0 dead vocabulary entries (excluding `Lit`/`FpLit`/`Random` escape hatches); every named predicate in `ValuePred`/`TuplePred`/`FpValuePred`/`FpTuplePred` exercised; `backward-report.md` records full hit counts |
| AC-5  | CLOSED | `VTypeEnvelope` immutable final class, `vill=false` always; smart-constructor is the only validated path (`copy`/`fromProduct`/`unapply` absent — verified via reflection in `VTypeEnvelopeTest`); `vsetvl/Tests.scala` future codepath scoped to DEC-9 (deferred but design recorded) |
| AC-6  | DEFERRED-RUNNER-READY | `scripts/run-release-matrix` invokes upstream Makefile for both DEC-6 release-blocking tuples (VLEN=256/XLEN=64/full march, VLEN=128/XLEN=64/full march); actual spike soak runs when environment has spike on PATH |
| AC-7  | CLOSED | `Driver.parseCli` covers every upstream `main.go` flag; tests verify CLI parity + `-configs` DEC-5 ignore + march parsing + `parseMarchExtensions` zvfh-implies-zvfhmin rule + embedded-ZVE `v`-drop |
| AC-8  | CLOSED | `MagicInstrEmit.encode` produces opcode-0x0B + rs1=group + rs2[0]=vxsat + rs2[4:1]=LMUL exactly per pspike contract; encode/decode round-trip covered for all 7 LMUL values × 6 groups × 2 vxsat states |
| AC-9  | CLOSED | `Driver.renderMakefrag` emits sorted `tests = \\\n  <name> \\\n` matching upstream format; `EmissionPipelineTest` verifies sort + every-target-has-file invariant |
| AC-10 | CLOSED (foundation) | `RvvInsn.vxrm` flag set for 22 insns (matches upstream toml metadata); `vxsat` for 14; `notestfloat3` for 2 with case sets ported to `notestfloat3/Vfredusum.scala` + `notestfloat3/Vfwredusum.scala` as Scala source-of-truth; FRM sweep wiring lives at task13's downstream test-block generator |
| AC-11 | CLOSED | `git diff` against upstream baseline shows zero edits under `pspike/`, `testfloat3/`, `macros/general/`, `env/`, `single/`; `Makefile` modification deferred to deployment (DEC-3 single-line generator-path swap via `scripts/rvprobe-driver` wrapper) |
| AC-12 | CLOSED | `Driver.scala` reads no `.toml` at runtime; audit `TomlIntent` and `DriftCheck` are offline @main tools |
| AC-13a | CLOSED | Every upstream curator tuple classified or Lit-fallbacked; `audit/waivers.md` carries the AC-13c-format template (empty entries; no waivers needed at closeout because Lit is sufficient per DEC-1) |
| AC-13b | DEFERRED | Predicate-exercise scan over rvprobe-EMITTED tests waits for AC-6's full soak |
| AC-13c | CLOSED | `audit/waivers.md` template enforces owner+reason+date(ISO-8601)+tracking-ID per entry |
| AC-14 | CLOSED | `RvvInsn.key = (extension, name)`; registry tests confirm `vfncvt.f.f.w` and `vfwcvt.f.f.v` each have 2 declarations (one in `v`, one in `zvfhmin`); stage1 filenames disambiguate via `<insn>_<ext>-N.S` |
| AC-15 | CLOSED-WIRED | `audit/DriftCheck.scala` `@main runDriftCheck` compares upstream toml SHA-256 against snapshot `contentHash`; returns exit 1 on drift with 3-commit refresh instructions; current state: in-sync (run output committed alongside) |
| AC-16 | CLOSED (emission side) | `PocGateTest` verifies all 9 POC instructions present in registry + emission produces well-formed `.S` (RVTEST_*, magic word, data section); spike-execution soak is part of AC-6 |
| AC-17 | CLOSED (foundation) | `OverlapLegality` with `DestNoVs1Overlap`/`DestNoVs2Overlap`/`DestNoMaskOverlap`/`WideningDestSourceOverlap` rules; `EewTest` covers all 7 LMUL values (Mf8/Mf4/Mf2/M1/M2/M4/M8) + footprint validation including computed-EMUL widening overflow case; full per-instruction overlap rule fan-out waits for task13's downstream wiring |
| AC-18 | CLOSED | `audit/SchemaInventory.scala` `@main writeSchemaInventory` renders `schema-inventory.md` byte-identical to the committed snapshot (verified in `SchemaTest`); re-verification at every snapshot regen |

## Cumulative Test Coverage

| Test file | Count |
|---|---|
| `SchemaTest` | 11 |
| `VTypeEnvelopeTest` | 17 |
| `EewTest` | 31 |
| `PredicateClassifierTest` | 28 |
| `TomlIntentTest` | 10 |
| `NotestFloat3Test` | 15 |
| `EmissionPipelineTest` | 28 |
| `InsnRegistryTest` | 11 |
| `PocGateTest` | 4 |
| **Total** | **155** |

## Pending User Decisions

All 5 DECs remain PENDING at closeout (acceptable per the plan's
"Pending User Decisions" framing; each is scoped to a specific future
task):

| DEC | Scope | Default position used in code | Where resolved |
|-----|-------|-------------------------------|----------------|
| DEC-5 (`-configs` flag) | task8 (Driver) | silently ignore | `Driver.parseCli` |
| DEC-7 (`riscv-vector-tests/` retention) | task19 cleanup | keep one release | this report |
| DEC-8 (duplicate-name keying default) | task8 + task15 | always key by (ext, name) | `RvvInsn.key` |
| DEC-9a/b/c (vill behavior) | task12 (vsetvl/Tests.scala) | envelope vill=false; trap+CSR per upstream | `VTypeEnvelope` invariant; task12 design pending |
| DEC-10 (future TOML sync) | task17 (drift CI) | manual sync via 3-commit pattern | `DriftCheck` exit message |

## Deferred / Runner-Ready Items

- **AC-6 spike soak** — `scripts/run-release-matrix` runner is committed; runs against `nix develop`-provided spike when present.
- **AC-13b emitted-test predicate exercise** — depends on AC-6's full corpus emission; the framework is in place (PocGateTest verifies for the 9 POC cases).
- **DEC-9 `vill=1` testing path** — design recorded; vsetvl/Tests.scala scaffold deferred pending user resolution of DEC-9a/b/c.

## BitLessons Codified (Session-Aggregate)

1. `BL-20260522-final-class-validated-construction` (round 1)
2. `BL-20260522-per-operand-profile-over-instruction-enum` (round 2)
3. `BL-20260522-singleton-enum-name-via-tostring` (round 4)
4. `BL-20260522-strip-comments-before-structural-parse` (round 5)
5. `BL-20260522-substring-vs-anchored-key-extraction` (round 6)

## Source Tree (Post-Closeout)

```
zaozi/rvprobe/src/rvv/
  Schema.scala                 — 39-entry sealed family
  vtype/
    VType.scala                — Sew/Lmul/Vta/Vma + legal-combo predicate
    VTypeEnvelope.scala        — immutable per-test envelope
  eew/
    Eew.scala                  — OperandClass + WidthScale + OperandWidthProfile + Eew/Emul/RegisterFootprint/NfieldsValidator
    OverlapLegality.scala      — DestNoVs1/Vs2/Mask overlap rules
  pred/
    ValuePred.scala            — 13-case integer element vocab
    TuplePred.scala            — 17-case operand-tuple vocab + ClassifyHint
    FpValuePred.scala          — 18-case FP element vocab
    FpTuplePred.scala          — 8-case FP tuple vocab
  audit/
    SchemaInventory.scala      — schema-inventory.md emitter
    TomlIntent.scala           — TOML parser + classifier + snapshot/report emitter
    InsnsGenerator.scala       — registry generator from snapshots
    DriftCheck.scala           — AC-15 drift CI
    snapshots/<ext>/*.json     — 676 per-toml audit fixtures
    forward-report.md          — AC-3 status
    backward-report.md         — AC-4 status
    waivers.md                 — AC-13c governance template
    schema-inventory.md        — AC-18 artifact
    closeout-report.md         — this file
  insns/
    V.scala                    — 629 RvvInsn declarations (v extension)
    Zvbb.scala                 — 16
    Zvbc.scala                 — 4
    Zvfbfmin.scala             — 2
    Zvfbfwma.scala             — 2
    Zvfhmin.scala              — 2
    Zvkg.scala                 — 2
    Zvkned.scala               — 11
    Zvknha.scala               — 3
    Zvksed.scala               — 3
    Zvksh.scala                — 2
    RvvInsnRegistryGenerated.scala — AllInsns.all aggregator
  notestfloat3/
    NotestFloat3InsnSource.scala — trait + FpCase
    Vfredusum.scala            — 27 cases
    Vfwredusum.scala           — 27 cases
  unittest/
    RvvInsn.scala              — per-instruction declaration carrier
    ElemValueLowering.scala    — predicate → witness vector (pure)
    MagicInstrEmit.scala       — opcode-0x0B encoder + decoder
    TestSEmit.scala            — full .S structure renderer
    Driver.scala               — CLI parity + walker + Makefrag emitter

zaozi/rvprobe/scripts/
  rvprobe-driver               — DEC-3 generator-path swap wrapper
  run-release-matrix           — AC-6 release soak runner
```
