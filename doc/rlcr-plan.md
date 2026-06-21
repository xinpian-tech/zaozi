# Metals / IDE Integration for Zaozi Dynamic Bundle Fields (Scala 3.8.4 Shadow Toolchain)

## Goal Description

Make Metals provide **completion**, **hover**, **go-to-definition**, and **find-references** for Zaozi's `Dynamic`-based **Bundle / ProbeBundle** field accesses (`io.a`). These break in the editor because `Referable[T] extends Dynamic` and `selectDynamic` is a `transparent inline` macro whose output lives in `Inlined.expansion`, which both Metals engines skip.

The fix (proven unreachable from a macro or a build compiler plugin) is to **bump zaozi to Scala 3.8.4** and shadow that exact version with a **patched fork** of two artifacts — `scala3-presentation-compiler_3:3.8.4` (completion/hover/definition) and `scala3-compiler_3:3.8.4` (find-references, via the SemanticDB extraction phase) — injected through the nix-controlled ivy/coursier cache so both consumers (zaozi's own compile and Metals' PC) load the patched jars. The whole chain is verified end-to-end by a **headless Metals LSP suite**, and de-risked **sentinel-first** (prove build→inject→Metals-loads-our-jar before any feature patch).

Scope is **Bundle/ProbeBundle `selectDynamic`** (plus the `applyDynamic` indexing of `Vec`/`Bits` reached through such fields). **Record/ProbeRecord are out of scope**: they extend `UntypedDynamicSubfield` and are accessed via an explicit `.field[T]("name")` string call, not `selectDynamic`, so the `io.a` navigation problem does not exist for them.

## Acceptance Criteria

- AC-1: **zaozi compiles and its full test suite is green on Scala 3.8.4.** (`build.mill` `v.scala = "3.8.4"`.)
  - Positive Tests (PASS): `nix develop -c mill __.tests.test` (utest suites for zaozi/circtlib/mlirlib/stdlib/decoder/smtlib/rvdecoderdb) all pass; lit suites `zaozi.lit.tests` and `stdlib.lit.tests` pass; macro-heavy modules (`-experimental`) compile.
  - Negative Tests (FAIL): a deliberately-broken Bundle field type still raises the macro's `errorAndAbort`; reverting `v.scala` to a version with no resolvable compiler fails resolution rather than silently using a mismatched compiler.
- AC-2: **The nix mif lock is regenerated for 3.8.4 and contains the full toolchain.**
  - Positive (PASS): regenerated `nix/zaozi/zaozi-lock.nix` contains `scala3-compiler_3:3.8.4`, `scala3-library_3:3.8.4`, `tasty-core_3:3.8.4`, `scala3-interfaces:3.8.4`, and the compiler bridge; a clean **offline** `mill --offline __.assembly` (sealed nix build) succeeds; `mill show zaozi.scalaCompilerClasspath` resolves offline.
  - Negative (FAIL): a lock missing `scala3-compiler_3:3.8.4` causes the sealed offline build to fail to resolve the compiler classpath (i.e. the current 3.7.4-library-only lock state is rejected, not tolerated).
- AC-3: **The patched fork jars build under nix as same-version `3.8.4` artifacts carrying a provenance sentinel.**
  - Positive (PASS): nix derivation emits `scala3-presentation-compiler_3:3.8.4` and `scala3-compiler_3:3.8.4` with a self-consistent ivy/coursier entry (jar + pom + metadata + checksums); the PC jar contains `dotty.tools.pc.ScalaPresentationCompiler`; the compiler jar contains the patched SemanticDB phase **`ExtractSemanticInfo`** (the `ExtractSemanticDB.PhaseMode.ExtractSemanticInfo` phase in `ExtractSemanticDB.scala`); a class-resource hash distinguishes each patched jar from stock; a stock-source build (sentinel only) produces compile output functionally identical to stock 3.8.4.
  - Negative (FAIL): a build that accidentally pulls the stock jar is detected because the sentinel class-resource hash / marker is absent.
- AC-4: **The shadow wins resolution in both consumers via an isolated authoritative cache, proven by an LSP-visible sentinel and JVM-side jar provenance.**
  - Mechanism (deterministic, not "overwrite stock in a shared cache"): an **isolated nix-provided `COURSIER_CACHE` / ivy repo is the sole authoritative source** for both JVMs (the Mill/`scalac` compiler JVM and the Metals/PC JVM); the user's `~/.cache/coursier` is never consulted. Coursier will not re-download a coordinate already present, so correctness comes from *isolation*, not from racing stock bytes under the same coordinate.
  - Positive (PASS): pointed at the isolated patched cache, both JVMs report the resolved `scala3-compiler_3:3.8.4` / `scala3-presentation-compiler_3:3.8.4` jar **path and content hash** equal to the patched artifacts (provenance asserted from the actual JVM, not just from a `coursier resolve`); a headless Metals `textDocument/completion` at `io.` returns the sentinel completion `__zaozi_marker__`; a dev-shell `scalac` observes the compiler sentinel.
  - Negative (FAIL): degradation is asserted, not silently ignored — specifically: pointed at an empty isolated cache, offline resolution FAILS (no patched bytes, nothing to fall back to); pointed at a stock/un-isolated cache (or online), the resolved jar hash is the stock hash and the sentinel is absent. A "dirty" shared cache that already contains stock `3.8.4` is rejected/bypassed by isolation rather than assumed to be overwritten.
- AC-5: **Completion** lists Bundle/ProbeBundle fields.
  - Positive (PASS): headless LSP `textDocument/completion` at `io.@@` lists every `BundleField`/`Option[BundleField]` field with a `Ref[E]`-style detail; honors a typed prefix `io.a@@`; no duplicate entries vs. ordinary members.
  - Negative (FAIL): completion at a non-Zaozi `Dynamic` qualifier does NOT inject Zaozi field items; a private/non-`BundleField` val is NOT offered.
- AC-6: **Hover** on `io.a` shows the field and its element type.
  - Positive (PASS): headless LSP `textDocument/hover` at `io.a@@` returns the field symbol and a defined type rendering (`Ref[E]` for `BundleField[E]`; the agreed rendering for `Option[BundleField[E]]`).
  - Negative (FAIL): hover on `io.<unknownField>` does not fabricate a field; hover on ordinary non-Zaozi code is unchanged.
- AC-7: **Go-to-definition** `io.a` → `val a`.
  - Positive (PASS): headless LSP `textDocument/definition` at `io.a@@` resolves to the bundle's `val a` `namePos`, for same-file AND cross-file (separately-compiled) bundle definitions.
  - Negative (FAIL): definition does not resolve to the `selectDynamic` method or the macro machinery; a misspelled field yields no bogus location.
- AC-8: **Find-references** `val a` → every `io.a`, via the patched `ExtractSemanticInfo` phase.
  - Positive (PASS): a fixture compiled by the patched compiler emits, in each `io.a` file's `.semanticdb`, a REFERENCE occurrence for `MyBundle#a.` at the `a` name span; headless LSP `textDocument/references` on `val a` returns all `io.a` use sites; covers `io.a` and nested chained `io.a.b` (both `selectDynamic`). (`applyDynamic` indexing shapes like `io.vec(0)` are stretch — see AC-11.)
  - Negative (FAIL): a fixture compiled by the STOCK compiler has no such occurrence (and find-references returns only the definition) — confirming the occurrence comes from the patch, not coincidence.
- AC-9: **Symbol-string consistency** between PC and compiler.
  - Positive (PASS): for every tested field shape, the SemanticDB symbol string the PC produces (definition path) equals byte-for-byte the symbol the compiler emits (reference occurrence); both derived from the compiler's own SemanticDB symbol mechanism, not a hand-rolled string builder. Shapes covered: top-level, package-nested, object-nested, and inner/nested bundle classes; local-bundle classes covered where Zaozi uses them. (Anonymous-class bundles are not a blocker unless Zaozi relies on that shape.)
  - Negative (FAIL): an intentionally divergent symbol derivation is caught by the consistency assertion (def/refs would mismatch).
- AC-10: **Graceful degradation** with the stock toolchain (invariant is project output, not the toolchain jar).
  - Positive (PASS): building zaozi's own **project sources** with the stock 3.8.4 compiler vs the patched compiler produces functionally identical compile products — bytecode and emitted Verilog/MLIR — with the ONLY difference being the extra `.semanticdb` reference occurrences; opening the project with stock Metals falls back to today's behavior with no build breakage. (This explicitly does NOT require the patched toolchain *jar* to equal the stock jar — it cannot, by construction.)
  - Negative (FAIL): a diff of stock-vs-patched zaozi compile products (excluding `.semanticdb`) shows any functional difference (bytecode/Verilog semantics changed) — which must not happen.
- AC-11: **Scope & no-regression.**
  - Positive (PASS): the four features work for Bundle/ProbeBundle; fork-internal PC tests show ordinary (non-Zaozi) completion/hover/definition unchanged; Record/ProbeRecord `.field("x")` access compiles and runs exactly as before (untouched). Stretch (optional): `applyDynamic` indexing shapes `io.vec(0)`, `io.bits(hi,lo)` resolve to the parent field name (occurrence at the field name, not the index/range arguments; `apply` and integer literals are not reported as the field).
  - Negative (FAIL): the resolver does NOT attempt to treat `Record`/`ProbeRecord` `selectDynamic` (they have none); general completion is not regressed by the additive Zaozi hook.

## Path Boundaries

This is a **highly deterministic design** (the draft fixes the mechanism: fork PC + patch compiler + nix same-version shadow + headless-LSP gate). Bounds are therefore narrow.

### Upper Bound (Maximum Acceptable Scope)
All four features (completion, hover, definition, references) for Bundle/ProbeBundle including the `applyDynamic` `Vec`/`Bits` indexing shapes; a single shared resolver object duplicated minimally across the two jars; the full headless Metals LSP gate plus fork-internal PC suites and SemanticDB expect-tests wired into `nix flake check`; sentinel/provenance + cache-state matrix; symbol-consistency assertion; nix packaging of both same-version jars with a self-consistent synthetic repo entry.

### Lower Bound (Minimum Acceptable Scope)
zaozi green on 3.8.4 (AC-1/AC-2); both patched jars build and win resolution proven by the LSP-visible sentinel + JVM-side jar provenance (AC-3/AC-4); the four features working for the **plain `io.a` Bundle case and chained `io.a.b`** (same-file and cross-file) gated by the headless Metals LSP suite (AC-5..AC-8); symbol consistency (AC-9); graceful degradation (AC-10). The `applyDynamic` `Vec`/`Bits` indexing shapes are **stretch** (AC-11 optional / M5), and Record-string navigation is out of scope — the lower bound deliberately excludes both, consistent with the required ACs.

### Allowed Choices
- Can use: fork `dotty.tools.pc.*` (PC) and patch `dotty.tools.dotc.semanticdb.ExtractSemanticDB` (`ExtractSemanticInfo` phase); same-version `3.8.4` shadow; nix-controlled ivy/coursier cache (`ivy-gather` / `COURSIER_CACHE`) as the injection mechanism; mill-ivy-fetcher (`mif`) for the lock; headless Metals LSP + scala3 fork-internal test harnesses; a shared resolver object copied into both jars.
- Cannot use: a Zaozi-side macro change or a build `-Xplugin` to fix navigation (proven ineffective, draft §2.3/§2.4); a custom version string like `3.8.4-zaozi` (must shadow same version); `~/.m2`/mavenLocal as the resolution mechanism (loses to Central); a hand-rolled SemanticDB symbol-string builder (use the compiler's own mechanism for the consistency invariant).
- Fixed per draft spec: the two-artifact fork, the same-version shadow, the headless-LSP blocking gate, and sentinel-first sequencing are not open choices.

## Feasibility Hints and Suggestions

> Reference only — not prescriptive.

### Conceptual Approach
1. Shared resolver (pure functions over dotc `Symbol`/`Type`/`Tree`): `isZaoziReferable`, `isDynamicSubfield` (Bundle/ProbeBundle only), `dynamicFields` (prefer typed `fieldSym.info` over `.tree` for TASTy robustness), `isDynamicAccessor`, `fieldNameAt`, `semanticdbSymbolOf` (via the compiler's SemanticDB symbol mechanism — identical in both jars).
2. PC completion: in the `case Select(qual, _) :: _` branch (`Completions.scala`), if `isZaoziReferable(qual.typeOpt)`, append synthetic field `CompletionValue`s (additive, type-gated, prefix-aware, dedup vs members).
3. PC hover/definition: add a case in `MetalsInteractive.enclosingSymbolsWithExpressionType` before the `Inlined` case (`MetalsInteractive.scala`) that resolves the field symbol; `HoverProvider` and `PcDefinitionProvider` reuse it unchanged.
4. Compiler find-references: in the `ExtractSemanticInfo` traverse (`ExtractSemanticDB.scala`, the `case tree: Inlined`), detect the Zaozi dynamic access and `registerUse(fieldSym, span, source)`; because this phase runs before `PostTyper`/`Inlining`, `Inlined.call` is the un-reduced `io.selectDynamic("a")` — read the name and span from `call`. Reuse existing occurrence guards.
5. nix: bump `v.scala`, regenerate the mif lock, build the two patched jars from a pinned 3.8.4 scala3 flake input, and inject them as a **self-consistent ivy/coursier entry inside an isolated, authoritative `COURSIER_CACHE`/ivy repo** that is the SOLE source for both the `mill --offline` compiler JVM and the Metals/PC JVM (never the user's `~/.cache/coursier`). Verify provenance from each JVM (resolved jar path + content hash). Launch Metals with that populated `COURSIER_CACHE`, using a **pinned Metals version** confirmed to resolve a Scala 3.8.x PC under nix/JDK 25.

### Relevant References
- `zaozi/src/reftpe/Referable.scala` — `Referable[T] extends Dynamic`; `selectDynamic`/`applyDynamic` macro entry points.
- `zaozi/src/magic/macros/Dynamic.scala` — macro expansion; field discovery; `Vec`/`Bits` `applyDynamic`.
- `zaozi/src/magic/DynamicSubfield.scala` — `DynamicSubfield` (Bundle/ProbeBundle) vs `UntypedDynamicSubfield` (Record/ProbeRecord).
- `zaozi/src/valuetpe/{Bundle,ProbeBundle,Record,ProbeRecord}.scala`; `zaozi/src/default/RecordApi.scala` (`.field`), `AsRecordViewApi.scala` (`asRecord`).
- scala3 3.8.4: `presentation-compiler/src/main/dotty/tools/pc/completions/Completions.scala`, `MetalsInteractive.scala`, `HoverProvider.scala`, `PcDefinitionProvider.scala`; `compiler/src/dotty/tools/dotc/semanticdb/ExtractSemanticDB.scala` (`ExtractSemanticInfo` phase); `compiler/src/dotty/tools/dotc/Compiler.scala` (phase ordering).
- nix: `build.mill` (`v.scala`), `nix/zaozi/zaozi.nix` (`passthru.bump`, `ivy-gather`, `mill --offline __.assembly`), `nix/zaozi/zaozi-lock.nix`, `flake.nix`.

## Dependencies and Sequence

### Milestones
1. **M0 — zaozi on Scala 3.8.4 (prerequisite).**
   - Phase A: bump `build.mill` `v.scala` to 3.8.4 (done); fix any 3.7→3.8 macro/experimental breakage.
   - Phase B: regenerate the mif lock; audit it contains the full 3.8.4 toolchain; confirm sealed offline build resolves the compiler.
   - Gate: full utest + lit suites green; `mill --offline __.assembly` succeeds.
2. **M1 — Build & package the patched fork (sentinel only).**
   - Step 1: pin scala3 3.8.4 source as a flake input.
   - Step 2: nix derivation builds `scala3-compiler_3` + `scala3-presentation-compiler_3` as same-version `3.8.4` jars + self-consistent ivy/coursier entry, with a provenance sentinel; stock-source behavior ≡ stock 3.8.4.
3. **M2 — Ship/inject the shadow.**
   - Prerequisite gate: a concrete Metals version is pinned and smoke-tested (task15) — the headless-LSP sentinel check cannot run otherwise.
   - Step 1: inject the M1 jars into the isolated authoritative nix `COURSIER_CACHE`/ivy repo for both consumers (zaozi build + Metals launch env).
   - Step 2: prove resolution end-to-end — sentinel observed through a headless Metals LSP completion and a dev-shell `scalac`, with JVM-side jar path+hash provenance, across empty/stock/patched cache states. (Depends on M1 and task15; de-risks the whole distribution mechanism before features.)
4. **M3 — Resolver core + completion.** Replace sentinel with the shared resolver (§Feasibility) + the PC completion hook. Gate: headless LSP completion lists Bundle fields. (Depends on M2.)
5. **M4 — Hover + go-to-definition + find-references.** One PC hook (hover+def) + the `ExtractSemanticInfo` patch (refs). Gate: LSP hover/definition/references over a BSP fixture (same+cross file) after a clean compile with stale `.semanticdb` deleted; symbol-consistency assertion. (Depends on M3 for the PC hook; refs depend on M1/M2 compiler patch.)
6. **M5 — Dogfood + CI.** Run the harness over real Zaozi sources; make `nix flake check` a blocking gate; optionally extend to `Vec`/`Bits` shapes. (Depends on M4.)
7. **M6 (stretch) — Upstream proposals** (generic PC dynamic-select hook; generic SemanticDB support for source-positioned macro-expansion references). (Depends on M4.)

Dependency summary: M0 → M1 → M2 → M3 → M4 → M5 → M6. Find-references (M4) additionally depends on the compiler patch shipped in M1/M2.

## Task Breakdown

| Task ID | Description | Target AC | Tag (`coding`/`analyze`) | Depends On |
|---------|-------------|-----------|----------------------------|------------|
| task1 | Bump `v.scala` to 3.8.4; fix 3.7→3.8 source breakage; get utest+lit suites green | AC-1 | coding | - |
| task2 | Regenerate mif lock; audit full 3.8.4 toolchain incl. compiler; confirm sealed offline build | AC-2 | coding | task1 |
| task3 | Analyze same-version shadow resolution precedence + self-consistent synthetic repo entry design | AC-3, AC-4 | analyze | - |
| task4 | Pin scala3 3.8.4 flake input; nix derivation building both patched jars with sentinel + ivy/coursier entry | AC-3 | coding | task3 |
| task5 | Inject shadow into isolated authoritative nix cache for both consumers; prove sentinel via headless Metals LSP + scalac with JVM-side jar provenance across cache states | AC-4 | coding | task2, task4, task15 |
| task6 | Implement shared resolver object (Bundle/ProbeBundle; typed symbol info; SemanticDB symbol mechanism) | AC-5, AC-9 | coding | task5 |
| task7 | PC completion hook in `Completions.scala` (additive, prefix-aware, dedup) | AC-5 | coding | task6 |
| task8 | PC hover/definition hook in `MetalsInteractive.enclosingSymbolsWithExpressionType` | AC-6, AC-7 | coding | task6 |
| task9 | Compiler find-references patch in `ExtractSemanticInfo` traverse (read name/span from un-reduced `Inlined.call`) | AC-8 | coding | task6 |
| task10 | Symbol-string consistency assertion (PC == compiler, byte-for-byte) across top-level/package/object/nested/local bundle shapes | AC-9 | coding | task8, task9 |
| task11 | Headless Metals LSP harness: reusable BSP fixture (also hosts the M2 sentinel check), `-Xsemanticdb`/`-sourceroot`/`-semanticdb-target` verification, stale-artifact cleanup (`.semanticdb`, `.bloop`, `.metals`, BSP output) and import/compile/index/PC-startup synchronization | AC-5, AC-6, AC-7, AC-8 | coding | task5, task15 |
| task12 | Fork-internal PC suites + SemanticDB expect-tests; field-shape + negative matrix; wire into `nix flake check` | AC-5..AC-11 | coding | task7, task8, task9, task11 |
| task13 | Graceful-degradation check (stock-vs-patched compiled output diff excluding `.semanticdb`) | AC-10 | coding | task9 |
| task14 | Dogfood over Zaozi sources; CI gate; optional Vec/Bits shapes | AC-8, AC-11 | coding | task12 |
| task15 | Determine + pin a concrete Metals version that resolves a Scala 3.8.x PC under nix/JDK 25; smoke-test it | AC-4 | analyze | task1 |

## Claude-Codex Deliberation

### Agreements
- The fix is unreachable from a Zaozi macro or a build `-Xplugin`; PC fork + compiler patch are required.
- Same-version shadow is the highest risk; needs a self-consistent synthetic ivy/coursier entry and a sentinel proving the exact jar bytes/classes load (not merely that resolution succeeds), tested across cache states.
- The mif compiler-classpath capture is unproven until a clean offline `mill scalaCompilerClasspath`/`__.assembly` shows the 3.8.4 (patched) compiler.
- The SemanticDB phase to patch at 3.8.4 is `ExtractSemanticInfo` (runs before `PostTyper`; `Inlined.call` un-reduced) — confirmed in `Compiler.scala`/`ExtractSemanticDB.scala`.
- Scope to Bundle/ProbeBundle `selectDynamic`; Record/ProbeRecord use `.field("x")` string access and are out of the `io.a` feature.
- Symbol consistency must reuse the compiler's SemanticDB symbol mechanism, asserted byte-for-byte; local/anonymous bundle classes need explicit tests.
- Resolver should prefer typed symbol info over `fieldSym.tree` for TASTy-loaded fields.

### Resolved Disagreements
- **Record/ProbeRecord coverage** (was DEC-1): draft §5 listed them as cases to cover; investigation shows they do not use `selectDynamic` (they extend `UntypedDynamicSubfield` and use `.field("x")` string access). Both Claude and Codex agree: explicitly out of scope for the `io.a` feature. String-literal navigation for `.field("x")` is recorded as possible separate future work, not a pending user decision.
- **ExtractSemanticDB phase framing**: draft hedged "before/after PostTyper". Resolved: at 3.8.4 the patch target is the `ExtractSemanticInfo` phase (in `ExtractSemanticDB.scala`), which runs **before** `PostTyper`; `Inlined.call` is un-reduced, so a single read-from-`call` path applies. AC-3/AC-8 corrected to name `ExtractSemanticInfo`.
- **Same-version shadow mechanism** (Codex round 1): resolved by isolation — an isolated authoritative `COURSIER_CACHE`/ivy repo for both JVMs, with JVM-side jar path+hash provenance, rather than racing/overwriting stock bytes in a shared cache. AC-4 rewritten accordingly.
- **`Vec`/`Bits` scope contradiction** (Codex round 1): resolved by moving `applyDynamic` indexing shapes to stretch (AC-11/M5); AC-8 requires only `io.a` and chained `io.a.b`; lower bound aligned.
- **AC-10 invariant strength** (Codex round 1): resolved — degradation is asserted on zaozi's project compile products (bytecode/Verilog/MLIR excluding `.semanticdb`), not on the toolchain jar (which cannot match stock).
- **Metals version** (was DEC-5): resolved into a required task (task15) to determine + pin + smoke-test a concrete Metals version, since a blocking LSP gate cannot rest on "recent enough". Per Codex round 2, task15 is wired as a **hard prerequisite** of M2/task5 and (transitively) M4; removed from pending decisions.

### Convergence Status
- Rounds executed: 2 (Codex first-pass + 2 reasonability rounds).
- Round 1: 4 `REQUIRED_CHANGES` (AC-3/AC-8 phase naming; AC-4 cache mechanism; Metals pin; Vec/Bits contradiction) + AC-10 strength — all applied.
- Round 2: 1 `REQUIRED_CHANGE` (wire task15 as a hard prerequisite of M2/M4) — applied; AC-4 empty-cache wording clarified.
- Final Status: `converged`. No `REQUIRED_CHANGES` remain. The three user decisions (DEC-2/DEC-3/DEC-4) have been resolved (see below); no `PENDING` items remain.

## Pending User Decisions

> DEC-1 (Record scope) and DEC-5 (Metals version) from candidate v1 were resolved during convergence and moved to "Resolved Disagreements" / task15. DEC-2/DEC-3/DEC-4 below were decided by the user; all decisions are now resolved.

- DEC-2: Editor launch — strictly via `nix develop`, or provide a generated Metals wrapper that exports `COURSIER_CACHE`/repos?
  - Claude Position: Document `nix develop` launch for M2-M4; add a wrapper in M5 if needed.
  - Codex Position: N/A - open question.
  - Tradeoff Summary: Wrapper improves UX but adds maintenance; affects AC-4 negative-test framing.
  - Decision Status: RESOLVED — launch via `nix develop` for M2-M4; a generated Metals wrapper is deferred to M5 only if needed.
- DEC-3: Find-references scope — workspace fixture/project only, or also into dependency/source jars?
  - Claude Position: Workspace-only for M4; dependency-jar navigation out of initial scope.
  - Codex Position: N/A - open question.
  - Tradeoff Summary: Cross-jar references need indexed `.semanticdb` for deps; larger harness.
  - Decision Status: RESOLVED — workspace/fixture only; dependency/source-jar references are out of initial scope.
- DEC-4: Should the sealed nix build compile zaozi itself with `-Xsemanticdb`, or is patched SemanticDB only required for user workspaces built by Metals/BSP?
  - Claude Position: Enable `-Xsemanticdb` for zaozi so dogfooding find-references on zaozi's own sources works (M5).
  - Codex Position: N/A - open question.
  - Tradeoff Summary: Affects whether AC-8 can be dogfooded on zaozi vs only on a fixture.
  - Decision Status: RESOLVED — enable `-Xsemanticdb` for zaozi's own build so AC-8 find-references is dogfooded on zaozi sources (M5).

## Implementation Notes

### Code Style Requirements
- Implementation code and comments must NOT contain plan-specific terminology such as "AC-", "Milestone", "Step", "Phase" (as workflow markers), or "sentinel-first". Use descriptive, domain-appropriate naming (e.g. `ZaoziPcSupport`, `ZaoziSemanticDB`, `zaoziFieldResolver`).
- Keep all Zaozi-specific logic isolated in one small object per jar (`ZaoziPcSupport.scala`, `ZaoziSemanticDB.scala`), called from upstream files in a few lines, to keep rebases against new scala3 releases cheap.

--- Original Design Draft Start ---

# Metals / IDE Integration Plan

This document is the design and implementation plan for making Metals (the Scala
language server) understand Zaozi's dynamically-accessed bundle fields, so that
**completion**, **hover**, **go-to-definition**, and **find-references** all work
on field accesses such as `io.a`.

It records *why* the obvious approaches do not work (so they are not re-attempted)
and *how* the chosen approach is wired end-to-end.

> Status: design accepted. First concrete step in progress — **bump zaozi to
> Scala 3.8.4** (the version we will shadow with a patched fork) and get the test
> suite green before any fork/PC work (§11, M0).
> Target toolchain: Scala **3.8.4**, JDK 25, a Metals build recent enough to
> resolve a Scala 3.8.x presentation compiler, nix-provisioned.

> **Decisions locked (2026-06):**
> 1. Shadow base = **Scala 3.8.4** (bump zaozi from 3.7.4 → 3.8.4; patch 3.8.4).
> 2. Blocking verification gate = **full headless Metals LSP suite** (drive a real
>    Metals server over a fixture workspace; assert `textDocument/*` responses).
> 3. **Sentinel-first**: prove build → inject → Metals-loads-our-jar with a trivial
>    marker patch *before* writing any real feature patch.

---

## 1. Problem statement

Zaozi exposes bundle fields through Scala 3's `Dynamic` trait. A user writes:

```scala
class MyBundle extends Bundle:
  val a = Aligned(UInt(8.W))
  val b = Flipped(Bool())

// ... elsewhere, io: Referable[MyBundle]
io.a := io.b
```

`io.a` is **not** a static member selection. `Referable[T] extends Dynamic`
(`zaozi/src/reftpe/Referable.scala:19`), so the compiler desugars `io.a` into
`io.selectDynamic("a")`, and `selectDynamic` is a `transparent inline` macro
(`Referable.scala:37`) that expands to
`io._tpe.asInstanceOf[DynamicSubfield].getRefViaFieldValName[T](io.refer, "a")`
(`zaozi/src/magic/macros/Dynamic.scala:61-138`).

Consequences in the editor:

- **No go-to-definition** from `io.a` to `val a`.
- **No hover** type/doc for `io.a`.
- **No completion** after `io.` (only the `Dynamic` machinery shows up).
- **No find-references** for a bundle field.

The macro produces *correct types* (it is `transparent inline`), so this is purely
an IDE-navigation problem, not a typing problem.

---

## 2. Root-cause analysis (load-bearing facts)

These facts were **re-verified against scala3 tag 3.8.4** (the new shadow base) and
the Zaozi sources. They determine the entire design; do not change the design
without re-checking them. All scala3 paths below are relative to a 3.8.4 checkout.

### 2.1 Metals routes features through two independent engines

| Feature | Engine | Where it lives |
| --- | --- | --- |
| completion | Presentation Compiler (PC) | `scala3-presentation-compiler` (`dotty.tools.pc`) |
| hover | Presentation Compiler | same |
| go-to-definition | Presentation Compiler first, then SemanticDB | PC + metals `DefinitionProvider` |
| find-references | on-disk `.semanticdb` index | metals `ReferenceProvider` |

- The **PC re-typechecks source in memory and never reads `.semanticdb`**
  (`VirtualFileParams` carries only `uri`/`text`). So patching `.semanticdb`
  cannot affect completion/hover/PC-resolved definition.
- On Scala 3 ≥ 3.7.0, metals `DefinitionProvider` tries the PC **first**
  (`fromCompiler` before `fromSemanticDb`), short-circuiting on a non-empty
  result. Zaozi targets 3.8.4, so go-to-definition is effectively a PC feature.
- **find-references is a SemanticDB feature**: `ReferenceProvider` builds a
  per-file Guava bloom filter from each `.semanticdb`'s `occurrence.symbol`
  values (`ReferenceProvider.onChange`), and only searches files whose bloom
  `mightContain` the target symbol (`pathsFor`). If a file's `.semanticdb` has
  no occurrence of `MyBundle#a`, that file is never even considered.

### 2.2 `transparent inline` wraps the macro output in `Inlined`, which both engines skip

After expansion, the tree at `io.a` is
`Inlined(call, bindings, expansion)` where the macro's output (and any symbol it
could reference) lives in `expansion`. **Verified at 3.8.4:**

- `ExtractSemanticDB` (the phase that writes `.semanticdb`) handles `Inlined` with
  `case tree: Inlined => traverse(tree.call)`
  (`compiler/src/dotty/tools/dotc/semanticdb/ExtractSemanticDB.scala:438-439`,
  inside `Extractor` at L217) — it traverses only `call`, **never `expansion`**.
  So no occurrence for the field is ever emitted, which is exactly why
  find-references fails.
- The interactive find-references traverser `Interactive.namedTrees` skips the
  same way: `case tree: untpd.Inlined => traverse(tree.call)`
  (`compiler/src/dotty/tools/dotc/interactive/Interactive.scala:206-207`,
  inside `namedTrees` L173-216).
- For the PC, `MetalsInteractive.enclosingSymbolsWithExpressionType`
  (`presentation-compiler/src/main/dotty/tools/pc/MetalsInteractive.scala`,
  method L114-278) has a case that, when the path head is `Inlined`, returns
  `List((call.symbol, head.typeOpt, None))` — i.e. the `selectDynamic` method,
  dropping the expansion (L171-172). `NavigateAST.pathTo` *does* descend into
  `expansion` via `productIterator`, but the macro-synthesized trees carry a
  synthetic, whole-`io.a` span and are filtered out by the PC's synthetic/
  zero-extent guards.

### 2.3 Macros cannot fix this from inside Zaozi

- `scala.quoted` exposes `Tree.pos` as a **getter only**; every tree factory
  stamps the synthetic, whole-call macro-expansion span via `withDefaultPos`.
  A macro cannot give a synthesized reference the precise source span of `a`.
- Even a perfectly-positioned reference would sit inside `Inlined.expansion`,
  which `ExtractSemanticDB` refuses to traverse (§2.2). So **find-references is
  unreachable from a macro**, full stop.

### 2.4 Metals does not load build compiler plugins into the PC

`CompilerPlugins.filterSupportedOptions` strips `-Xplugin:` from the PC's options
unless the plugin is on a hardcoded allow-list (`kind-projector`, `bm4`). So a
build-side compiler plugin cannot influence completion/hover/definition.

### 2.5 Conclusion

- The three interactive features (completion, hover, definition) can only be
  fixed **inside the presentation compiler** → fork `scala3-presentation-compiler`.
- find-references can only be fixed by getting real occurrences into
  `.semanticdb` → either an external `-Xplugin` post-processor (works, but extra
  wiring) **or** patch `ExtractSemanticDB` in the compiler. Because we are
  shipping a shadowed toolchain anyway (§4, §7), we **bake the fix into the
  compiler** and drop the plugin idea entirely.

---

## 3. Goals and non-goals

**Goals**

- `io.<TAB>` lists the bundle's fields with their `Ref[_]` types.
- Hover on `io.a` shows the field and its type.
- Go-to-definition on `io.a` jumps to `val a` in the bundle (same file and
  cross-file).
- Find-references on `val a` finds every `io.a` usage in the workspace.
- **No change to a project's `scalaVersion` string beyond the 3.8.4 bump** — we
  shadow the official Scala 3.8.4 artifacts with patched ones of the same version,
  provisioned by nix.
- Graceful degradation: a project built with the stock toolchain still compiles
  identically; it only loses the IDE niceties.

**Non-goals**

- Changing the Zaozi user-facing API (`io.a` syntax stays).
- Upstreaming to dotty/metals in the first iteration (tracked as future work in §9).
- Supporting editors that launch Metals outside the nix-provisioned environment
  (documented limitation, §7.4).

---

## 4. Architecture decision

Fork **two** Scala 3 artifacts and publish them under the **same version string**
(`3.8.4`), served from the nix-controlled ivy/coursier cache that zaozi already
uses (§7):

| Artifact | Patched for | Modules touched |
| --- | --- | --- |
| `scala3-presentation-compiler_3:3.8.4` | completion, hover, go-to-def | `dotty.tools.pc.*` |
| `scala3-compiler_3:3.8.4` | find-references | `dotty.tools.dotc.semanticdb.ExtractSemanticDB` |

`scala3-library_3` is **unchanged**; do not republish it.

Rationale for "shadow same version" over "custom version `3.8.4-zaozi`":

- No project config churn beyond the one 3.8.4 bump; users keep
  `scalaVersion := "3.8.4"` (`build.mill` `v.scala`).
- Verified safe against Metals' built-in fast path: that path triggers only when
  the project's Scala version equals Metals' *own* Scala version (a 2.13.x),
  never a Scala 3 version. For Scala 3 projects Metals always resolves the PC
  from coursier/Maven, so a shadowed PC jar *is* picked up.
- The one real constraint is resolution precedence (§7): same GAV+version means
  we must control the ivy/coursier cache contents, which nix already does.

Both the PC fork and the compiler patch share **one resolver** (§5) so the symbol
strings they produce are identical (required for definition/references to line up).

Two distinct consumers load these patched jars (§7.3):

- **zaozi's own build** compiles with `scala3-compiler_3:3.8.4`. To get
  find-references occurrences into zaozi's *own* `.semanticdb`, zaozi must be
  compiled by the **patched** compiler.
- **Metals' PC** is resolved per project Scala version → it must resolve the
  **patched** `scala3-presentation-compiler_3:3.8.4`.

---

## 5. Shared core: the Zaozi field resolver

To keep the PC fork and the compiler patch in sync and to minimize the rebase
surface, all Zaozi-specific knowledge lives in **one small object**, duplicated as
little as possible across the two artifacts (they cannot share a jar, so this is
a copied source file or a tiny shared support module pulled into both builds).

Responsibilities (pure functions over dotc `Symbol`/`Type`/`Tree`):

1. `isZaoziReferable(tpe): Boolean` — `tpe <:< me.jiuyang.zaozi.reftpe.Referable[?]`.
2. `isDynamicSubfield(tpe): Boolean` — `tpe <:< me.jiuyang.zaozi.magic.DynamicSubfield`
   (Bundle / ProbeBundle) or the Record/ProbeRecord equivalents.
3. `dynamicFields(referableTpe): List[FieldInfo]` — given `Referable[T]`, return
   `T`'s fields whose declared type is `BundleField[E]` or `Option[BundleField[E]]`,
   each with: field `Symbol`, field `name`, element type `E`, and the field's
   definition `SourcePosition`. This mirrors the macro's own logic
   (`Dynamic.scala:80` `classSymbol.declaredFields.find(...)`, and the
   `BundleField[?]` / `Option[BundleField[?]]` discrimination at
   `Dynamic.scala:97-138`).
4. `isDynamicAccessor(sym): Boolean` — `sym` is `Referable.selectDynamic`,
   `applyDynamic`, or `applyDynamicNamed`.
5. `fieldNameAt(call/expansion, pos): Option[String]` — recover the accessed
   field name. Prefer the `"a"` string literal carried in the expanded
   `getRefViaFieldValName(..., "a")` call; fall back to the `selectDynamic`
   call's literal argument.
6. `semanticdbSymbolOf(fieldSym): String` — the canonical SemanticDB symbol
   (`pkg/MyBundle#a.`). **Must be identical** in both artifacts.

Cases to cover: `Bundle`, `ProbeBundle` (`selectDynamic`); `Record`, `ProbeRecord`
(case-class `BundleField` introspection, cf. recent `asRecord` work); and the
`applyDynamic` indexing paths for `Vec`/`Bits` (`Dynamic.scala:179-248`). Start
with `Bundle`/`ProbeBundle` and grow.

---

## 6. Implementation per capability

All scala3 paths below are relative to a **scala3 3.8.4** checkout. Line numbers
were verified against tag 3.8.4 (re-check on every rebase; §9).

### 6.1 Completion (`io.<TAB>`)

- File: `presentation-compiler/src/main/dotty/tools/pc/completions/Completions.scala`.
- Hook: the `case Select(qual, _) :: _` branch (**L175-177**) that calls
  `enrichedCompilerCompletions(qual.typeOpt.widenDealias)` (the `completions()`
  method starts at L161).
- Change: if `isZaoziReferable(qual.typeOpt)`, append synthetic
  `CompletionValue`s, one per `dynamicFields(qual.typeOpt)`:
  - label = field name (`a`),
  - detail = `Ref[E]` (or the precise propagated type),
  - insertText = field name,
  - completionItemKind = Field.
- This is additive and gated on the Zaozi type, so it cannot regress general
  completion. It does **not** require patching the lower-level
  `dotc.interactive.Completion.accessibleMembers` (which would force a
  `scala3-compiler` rebuild) — keep it in the PC module.

### 6.2 Hover (`io.a`)

- File: `presentation-compiler/src/main/dotty/tools/pc/MetalsInteractive.scala`.
- Hook: `enclosingSymbolsWithExpressionType` (method L114-278), **before** the
  existing `case (head @ Inlined(call, bindings, expansion)) :: _ =>
  List((call.symbol, head.typeOpt, None))` (**L171-172**).
- Change: add a case — if the path head is an `Inlined` whose `call`/`expansion`
  is a Zaozi dynamic access (`isDynamicAccessor`), resolve the field via §5 and
  return `(fieldSym, fieldType, None)`.
- `HoverProvider` (`presentation-compiler/.../HoverProvider.scala:101-106`) reuses
  this machinery (its only call site of `enclosingSymbolsWithExpressionType`), so
  hover is fixed by the same change.

### 6.3 Go-to-definition (`io.a` → `val a`)

- File: `presentation-compiler/src/main/dotty/tools/pc/PcDefinitionProvider.scala`.
- No new hook needed beyond §6.2: `PcDefinitionProvider.findDefinitions` (L83-94)
  calls `MetalsInteractive.enclosingSymbols` (L91); once that returns `fieldSym`,
  `locationsForSymbol` (L131-148) resolves the location:
  - same-file: `Interactive.findTreesMatching` → the field's `namePos.toLsp`
    (L139-147);
  - cross-file: `search.definition(semanticdbSymbol, …)` against the global
    symbol index (L148). This works without the compiler patch, because the
    field's **definition** occurrence (`val a`) is recorded normally — only the
    *use* site was hidden in `Inlined`.
- Strategy ordering: on 3.8.4 the PC runs first and now returns the correct
  symbol, so no change to metals `DefinitionProvider` is required.

### 6.4 Find-references (`val a` → all `io.a`)

- File: `compiler/src/dotty/tools/dotc/semanticdb/ExtractSemanticDB.scala`.
- Hook: the `Extractor.traverse` `case tree: Inlined` (**L438-439**), and/or the
  `Select`/`Apply` shape of the desugared `selectDynamic` call.
- Change: when a Zaozi dynamic access is detected, resolve `fieldSym` via §5 and
  `registerUse(fieldSym, span, source)`:
  - Inside the compiler phase we have full `tpd`/`Span` access (unlike a macro),
    so we can choose a real span. Prefer the `selectDynamic` select's `nameSpan`
    (the `a` source range); fall back to the `Inlined.call` span (the whole
    `io.a`), which is still functional for references and for
    SemanticDB-driven definition.
  - Reuse the existing guards (`registerUseGuarded`, `selectSpan` style) so the
    emitted occurrence matches the format of ordinary selects.
- Verify phase ordering relative to `PostTyper` (`Compiler.scala`): if
  `ExtractSemanticDB` runs before `PostTyper`, `Inlined.call` still holds the full
  `io.selectDynamic("a")` and its name span; if after, `call` is reduced and we
  fall back to reading the name from `expansion` + a coarser span. Both paths must
  be handled.
- Result: every `io.a` file's `.semanticdb` carries a `MyBundle#a` REFERENCE
  occurrence → metals `ReferenceProvider` bloom filter includes the file →
  find-references works with **no metals fork**.

---

## 7. Distribution & nix wiring

### 7.1 How zaozi's toolchain is already provisioned (mif)

This is the foundation the shadow injection builds on. zaozi pins its Scala
version in **one place** — `build.mill` `v.scala` (currently `3.8.4`) — and all
modules read it via `def scalaVersion = Task(v.scala)`.

Nix dependency provisioning uses **mif** (`mill-ivy-fetcher`, flake input
`github:Avimitin/mill-ivy-fetcher`):

- **Lock generation.** `nix/zaozi/zaozi.nix` exposes `passthru.bump` =
  `bump-zaozi-mill-lock`, which runs `mif run -p "${src}" -o
  ./nix/zaozi/zaozi-lock.nix`. Internally mif (`Prepare.scala:107-113`) runs, per
  build target, `<t>.prepareOffline`, `<t>.scalaCompilerClasspath`, and
  `<t>.scalaDocClasspath` against a clean coursier cache
  (`COURSIER_REPOSITORIES=ivy2local|central`), then scans every `.pom` in that
  cache and emits one `fetchurl`/`fetchMaven` entry per artifact
  (`nix/zaozi/zaozi-lock.nix`, ~362 entries).
- **Lock consumption.** `ivy-gather ./zaozi-lock.nix` builds a derivation
  containing the ivy/coursier repo layout; it is a `buildInput` of the zaozi
  derivation.
- **Sealed build.** `buildPhase` runs `mill --no-daemon --offline '__.assembly'`
  — fully offline, relying entirely on the ivy-gather'd cache. (Carries a
  `# FIXME: wait https://github.com/com-lihaoyi/mill/pull/5521` re offline
  assembly.)
- **Dev shell.** `nix develop` uses `mill-ivy-env-shell-hook` to set up the same
  ivy cache, but is **not** `--offline`, so mill can still fetch missing deps via
  the configured http(s) proxy. (This is why a `v.scala` bump compiles in the dev
  shell *before* the lock is regenerated — the sealed build cannot.)

**Bump consequence (M0).** After bumping `v.scala`, the lock is stale and **must
be regenerated** (`nix run`-style invocation of `passthru.bump`). A fresh `mif
run` *does* request `scalaCompilerClasspath`, so it should capture
`scala3-compiler_3:3.8.4`. Verify the regenerated lock contains the full 3.8.4
toolchain (compiler + sbt-bridge + tasty-core + library) — the *current* lock is
a cautionary example: it carries `scala3-library_3-3.7.4` but **no**
`scala3-compiler_3:3.7.x` (the only complete toolchains in it are 3.6.2 tooling
and mill's own 3.8.1 runtime), i.e. a stale/partial regen.

### 7.2 What to build (patched jars)

Patched, same-version artifacts:

- `org.scala-lang:scala3-presentation-compiler_3:3.8.4` (PC fork, §6.1-6.3)
- `org.scala-lang:scala3-compiler_3:3.8.4` (ExtractSemanticDB patch, §6.4)
- `scala3-library_3` is **unchanged**; do not republish it.

Keep PC and compiler consistent (the PC depends on the compiler). Built from a
**pinned scala3 3.8.4 source** (a flake input) so the whole thing is reproducible.

### 7.3 Making the shadow win resolution

Two consumers, one mechanism (the nix-controlled cache):

- **zaozi's build** (`mill --offline`) resolves `scala3-compiler_3:3.8.4` from the
  ivy-gather'd cache. Inject the **patched** compiler jar there (either by feeding
  the patched jar into the lock / a parallel ivy-gather, or by overriding the
  cache entry) so zaozi is compiled by the patched compiler → its own
  `.semanticdb` gets the field-reference occurrences.
- **Metals' PC** resolves `scala3-presentation-compiler_3:3.8.4` with coursier's
  `FileCache()`, which honors `COURSIER_CACHE`. Pre-populate that cache (the same
  nix mechanism) so the patched PC jar is picked up and Central is never consulted.

Verified resolution constraints from Metals source:

- `Embedded.repositories` orders `Resolve.defaultRepositories` (coursier cache +
  Maven Central) **before** `~/.m2` (mavenLocal). Publishing to `~/.m2` under the
  same GAV+version does **not** beat Central — control the coursier cache instead.
- Coursier will **not** re-download a version already present in the cache.

### 7.4 Making Metals use it

- **Metals must be launched with the same `COURSIER_CACHE`** that nix populated
  (e.g. start the editor from within `nix develop`, or have the editor's Metals
  config export `COURSIER_CACHE`). If Metals starts outside the dev shell it
  resolves the stock 3.8.4 PC from Central and the specialization silently
  disappears.
- The find-references path additionally requires the project to have been
  **compiled by the patched compiler** (the occurrences live in `.semanticdb`).
  Since nix provides the compiler, normal builds satisfy this; stale `.semanticdb`
  from a previous stock build must be regenerated (clean + recompile).

### 7.5 Degradation outside nix

- The compiler patch is purely additive to `.semanticdb`; the stock compiler just
  omits the extra occurrences. Code compiles identically.
- The PC fork is editor-only. Without it, navigation falls back to today's
  (broken) behavior. No build breakage either way.

---

## 8. Verification (programmatic, layered)

The blocking gate is the **full headless Metals LSP suite** (decision §0). The
strategy is a pyramid: three nested loops plus one invariant. Each milestone's
"done" = its gate is green in `nix flake check`.

### 8.0 Layer 0 — provenance / sentinel

"Is my patched jar even the one running?" Decouples *distribution correctness*
from *feature correctness*, making the §7.3/§10 "shadow doesn't win" risk a
one-line assert instead of an editor surprise. A class-presence check
(`dotty.tools.pc.ScalaPresentationCompiler`, `…dotc.semanticdb.ExtractSemanticDB`)
plus a behavioral sentinel that is **observable through the LSP** — e.g. the
shadowed PC injects a magic completion item `__zaozi_marker__`, so a headless
`textDocument/completion` at `io.` returns it. This is the M2 gate. Runs on every
fork build.

### 8.1 Layer 1 — fork-internal suites (fast inner loop)

Runs inside the scala3 fork build; tests the *source*, not the shipped jar.
scala3 already ships the harnesses: `presentation-compiler/test`
(`CompletionSuite`, `HoverTermSuite`, `PcDefinitionSuite`, caret `@@` fixtures)
and the semanticdb expect-tests (`tests/semanticdb/expect`, compile + diff against
a checked-in dump). Add Zaozi fixtures to each. Cheap; catches logic regressions
on rebase.

### 8.2 Layer 2 — full headless Metals LSP (blocking gate)

A real Metals server driven over a fixture **BSP-buildable** workspace, with the
nix-provisioned `COURSIER_CACHE` (so it resolves our shadowed PC) and the project
compiled by the patched compiler (so `.semanticdb` carries the occurrences). The
harness sends `initialize` → `didOpen` → `textDocument/{completion,hover,
definition,references}` and asserts the JSON responses (mirrors metals' own
`*LspSuite`). This is the only layer that exercises the full editor path —
resolution + PC patch + compiler patch + metals plumbing — end to end. Cost:
heavier; pulls metals + a buildable fixture into CI (references need on-disk
`.semanticdb` via the build server).

### 8.3 Symbol-consistency invariant (cross-cutting)

The most fragile contract (§4/§5.6): assert the SemanticDB symbol string the PC
path produces **==** the one the compiler path produces for the same field.
Definition-via-PC and references-via-semanticdb only line up if these match
byte-for-byte. Make it a first-class assertion, not a doc note.

---

## 9. Maintenance & upstreaming

- **Isolate the delta.** Put all Zaozi logic in a single `ZaoziPcSupport.scala`
  (PC) and `ZaoziSemanticDB.scala` (compiler), each a thin object the upstream
  files call in 3-5 lines. This keeps rebases against new scala3 releases cheap.
- **Pin the scala3 source** as a flake input; bump deliberately, re-verify the §2
  facts and §6 line numbers, re-run the test suite, re-publish the two jars under
  the new version.
- **Symbol-string invariant** (§5.6) is the most fragile cross-cutting contract;
  the consistency test (§8.3) protects it.
- **Future upstreaming**: the principled, fork-free version is (a) a PC extension
  point / SPI so a DSL can register a "dynamic select" navigation resolver, and
  (b) making `ExtractSemanticDB` optionally descend into macro `Inlined.expansion`.
  Propose these to dotty/metals once the zaozi-specific version has proven the UX.
  Track as a separate effort; not a blocker for this plan.

---

## 10. Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| 3.8.4 bump breaks zaozi build/tests (macro/experimental code) | M0 blocked | bump + green test suite is M0, gating everything (§11) |
| mif lock missing the patched/3.8.4 compiler (cf. stale 3.7.4 lock) | sealed build fails / uses stock compiler | regenerate lock; assert full 3.8.4 toolchain present (§7.1) |
| Metals launched without the nix `COURSIER_CACHE` | features silently absent | document launch env (§7.4); consider a doctor/check |
| Same-version shadow not winning resolution | stock PC/compiler used | control the nix ivy/coursier cache, never rely on `~/.m2` (§7.3); sentinel gate (§8.0) |
| Metals build too old for Scala 3.8.x PC | PC won't resolve | pin a Metals recent enough for 3.8.x |
| Headless-LSP harness complexity (BSP fixture, metals in CI) | slow/fragile gate | Layer 1 as fast inner loop; Layer 2 only as the blocking gate |
| scala3 release churn breaks the patch | rebase cost | isolate delta (§9), pin source, re-verify §2/§6, test suite |
| `ExtractSemanticDB` vs `PostTyper` ordering | coarse/empty span | handle both call shapes (§6.4) |
| PC vs compiler symbol-string drift | def/refs mismatch | shared resolver (§5) + consistency test (§8.3) |
| Record/Vec/Bits paths uncovered | partial feature | phase coverage; start with Bundle (§5) |

---

## 11. Milestones

Infra-first, then features. Each milestone's definition-of-done is its
verification gate (§8) green in `nix flake check`.

1. **M0 — Prerequisite: zaozi on Scala 3.8.4.** Bump `build.mill` `v.scala`
   (done), regenerate the mif lock (§7.1), get the **dev-shell build + full test
   suite green**, and confirm the sealed nix build resolves the 3.8.4 toolchain.
   *Gate:* `__.tests.test` (utest) + lit tests pass; sealed `__.assembly`
   resolves offline.
2. **M1 — Build & package the patched fork.** nix derivation builds
   `scala3-compiler_3` + `scala3-presentation-compiler_3` from the pinned 3.8.4
   fork source → same-version `3.8.4` jars + coursier metadata. **Stock source +
   a sentinel marker, no real feature patch yet.** *Gate:* jar has the expected
   classes; sentinel present; smoke-compile output ≡ stock 3.8.4.
3. **M2 — Ship/inject the shadow.** Inject the M1 jars into the nix ivy/coursier
   cache (zaozi build + Metals launch env) so `3.8.4` resolves to them, Central
   never consulted. *Gate:* the **sentinel is observed through a headless Metals
   LSP `textDocument/completion`** (proves resolution + Metals-loads-our-jar end
   to end), and from a dev-shell `scalac`.
4. **M3 — Resolver core (§5) + completion (§6.1).** Swap the sentinel for the real
   resolver + completion hook. *Gate:* headless Metals LSP completion at `io.@@`
   lists the bundle fields with `Ref[_]` types.
5. **M4 — Hover + go-to-definition (§6.2-6.3) + find-references (§6.4).** One PC
   hook unlocks hover+def; the `ExtractSemanticDB` patch unlocks refs. *Gate:*
   LSP `hover`/`definition`/`references` over a fixture (same-file + cross-file);
   plus the symbol-consistency assertion (§8.3).
6. **M5 — Dogfood + CI.** Run the harness over the real Zaozi sources; make
   `nix flake check` a blocking CI gate; grow resolver coverage to
   Record/ProbeRecord/Vec/Bits (§5).
7. **M6 (stretch) — upstream proposals (§9).**

Recommended path: **M0 → M1 → M2** de-risks the entire distribution mechanism
(does the same-version shadow actually win resolution, and does Metals load it?)
*before* any feature code, exactly because the patches themselves are small,
localized hooks (§6) while resolution is the real unknown (§7).

--- Original Design Draft End ---
