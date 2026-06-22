# Same-Version Shadow Injection — Implementation Contract (task3 / AC-3, AC-4)

> Design deliverable for the Metals integration. Specifies **how** the patched,
> same-version `scala3-compiler_3:3.8.4` and `scala3-presentation-compiler_3:3.8.4`
> jars are emitted and made to **win resolution** over stock Maven Central for both
> consumers, without changing the version string. This is the contract that task4
> (build patched jars + emit cache entry) and task5 (inject + prove) build against.
> Authored from verified repo mechanics; cross-checked by a Codex analysis pass
> (see "Codex cross-check" at the end).

## 0. The two consumers and the core trick

- **Consumer A — zaozi's own build (Mill `scalac`)** resolves `scala3-compiler_3:3.8.4`.
  The patched compiler is needed so zaozi's own `.semanticdb` later carries the
  field-reference occurrences (AC-8 dogfood).
- **Consumer B — Metals' presentation compiler** resolves
  `scala3-presentation-compiler_3:3.8.4` (per the project Scala version) via
  coursier `FileCache()` honoring `COURSIER_CACHE`.

Core trick (verified): **coursier will not re-download a coordinate already
present in its cache.** So correctness comes from *isolation* — pre-populate an
isolated, authoritative cache with the patched bytes under the exact stock
coordinate path, point both JVMs at only that cache, and Central is never
consulted. We do **not** try to race/overwrite stock bytes in a shared cache.

## 1. On-disk layout per patched artifact

### 1a. Coursier cache layout (verified ground truth)
For a Maven artifact, coursier's `FileCache` stores, under
`$COURSIER_CACHE/https/repo1.maven.org/maven2/<org-path>/<artifact>/<version>/`:

```
scala3-compiler_3-3.8.4.jar
scala3-compiler_3-3.8.4.pom
.scala3-compiler_3-3.8.4.jar.checked        # "verified/fetched" marker (presence => no re-check)
.scala3-compiler_3-3.8.4.jar__sha1          # remote checksum sidecar
.scala3-compiler_3-3.8.4.jar__sha1.computed # locally computed checksum
.scala3-compiler_3-3.8.4.jar__md5
.scala3-compiler_3-3.8.4.pom.checked
.scala3-compiler_3-3.8.4.pom__sha1
.scala3-compiler_3-3.8.4.pom__sha1.computed
.scala3-compiler_3-3.8.4.pom__md5
```
(Confirmed by inspecting the live `out/.coursier` cache for both
`scala3-compiler_3/3.8.4` and `scala3-library_3/3.8.4`.)

For a PATCHED jar the required files are: the patched **`.jar`**, a **`.pom`**
(reuse the stock pom verbatim — same coordinate, same deps), and the sidecars
**recomputed for the patched jar** (`__sha1`/`__md5` + the `.checked` marker), so
coursier treats the coordinate as already resolved and never reaches the network.
The `.pom` and its sidecars can be the stock ones unchanged (the pom describes
dependencies/coordinate, which are unchanged by the patch).

### 1b. How this reconciles with the existing `fetchMaven` / `ivy-gather`
`nix/zaozi/zaozi-lock.nix` `fetchMaven` downloads `{jar,pom}` from the URL (fixed
`hash`) and `ivy-gather` lays them under `installPath`
(`https/repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.8.4`). The sealed
`mill --offline __.assembly` consumes that. **Two viable injection strategies for
the patched compiler (task4 picks one; recommended = B):**

- **Strategy A — patch the lock entry.** Replace the `scala3-compiler_3-3.8.4`
  `fetchMaven` entry so its source is the locally-built patched jar (a nix store
  path / `file://`) instead of the Central URL, with the patched `hash`. ivy-gather
  then lays the patched jar at the same `installPath`. Pro: reuses the whole
  sealed-build path unchanged. Con: `fetchMaven` is URL+hash oriented; needs a
  local-source variant.
- **Strategy B (recommended) — overlay derivation.** A nix derivation
  `patched-ivy-cache` = `ivy-gather ./zaozi-lock.nix` with the patched
  `compiler` (and, for Metals, the `presentation-compiler`) jar + recomputed
  sidecars **overlaid** at the stock `installPath` (replace jar bytes, keep pom,
  regenerate `__sha1`/`__md5`/`.checked`). Both consumers point at this overlaid
  cache. Pro: cleanly separates "stock deps" from "patched overlay"; the PC jar
  (not a zaozi build dep, so absent from the lock) is simply added here.

Note: Mill resolves the Scala compiler via coursier (the lock entries are coursier
cache paths, not ivy2local `ivys/ivy.xml`), so the coursier-tree layout above is
the one that matters; an ivy2local layout is **not** required for the compiler.
(Confirm during task4 that Mill does not separately demand `local/.../ivys/ivy.xml`
for the scala compiler — the existing lock has no such entries and the sealed build
works, which is strong evidence it does not.)

## 2. Provenance marker

Two independent signals, both cheap and removable:

- **Jar provenance (class-resource hash).** task4 computes and records the content
  hash of each patched jar. The probe (section 4) asserts the *loaded* jar's hash
  equals the patched hash (distinguishes patched from stock without any behavioral
  change). Optionally embed a marker resource file (e.g. `zaozi-shadow.marker`
  containing the fork git rev) in each jar — trivial to grep, removable.
- **Behavioral marker (sentinel-first gate).** Until real features land:
  - **PC**: inject a synthetic completion item `__zaozi_marker__` so a headless
    Metals `textDocument/completion` at `io.` returns it — proves Metals loaded the
    patched PC end to end.
  - **Compiler**: emit a marker `.semanticdb` occurrence (or a compiler build
    property / `-Vphases` entry showing the patched `ExtractSemanticInfo`) so a
    dev-shell compile shows the patched compiler ran.
  Both are removed/replaced when the real resolver + hooks land (task6-9).

## 3. Isolated authoritative cache ownership

nix builds the isolated cache (section 1b Strategy B) and **both** JVMs are pointed
at it as the sole source:

- **Consumer A (Mill/scalac).** Set `COURSIER_CACHE=<patched-cache>` and
  `COURSIER_REPOSITORIES=ivy2local|central` is NOT used for the shadow — instead
  rely on the offline cache. For the sealed build this is already how
  `ivy-gather` + `mill --offline` works; for the dev shell, override the
  `mill-ivy-env-shell-hook` so coursier/ivy home points at the patched cache.
- **Consumer B (Metals/PC).** Launch Metals with `COURSIER_CACHE=<patched-cache>`
  (DEC-2 resolution: via `nix develop` for now; a wrapper later). Metals' `Embedded`
  uses `FileCache()` honoring `COURSIER_CACHE`.
- **Never consult `~/.cache/coursier`.** Set `COURSIER_CACHE` explicitly for both;
  do not rely on the default.
- **GOTCHA (verified in Round 1/2).** The dev-shell mill-ivy hook injects
  `-Divy.home`/`-Dcoursier.ivy.home`/`-Duser.home` into `JAVA_TOOL_OPTIONS` and
  `JAVA_OPTS`, and Java takes the **last** `-D` value — these can override an
  intended cache. Any injection that sets cache dirs via JVM props must ensure its
  values win (clean `JAVA_TOOL_OPTIONS`, as the mif regen required), or set the
  cache purely via `COURSIER_CACHE` env (which the `-D` props do not override).

## 4. JVM-side provenance probes

Assert from the ACTUAL JVMs, not a resolver-only command:

- **Mill/scalac (Consumer A):** `mill show zaozi.scalaCompilerClasspath` prints the
  resolved jar paths; pipe the `scala3-compiler_3-3.8.4.jar` path to `sha256sum` and
  assert it equals the patched hash. (Stronger than `coursier resolve` because it is
  the path Mill will actually feed scalac.) Optionally `unzip -p <jar>
  zaozi-shadow.marker`.
- **Metals/PC (Consumer B):** introspect which PC jar Metals loaded — via the
  Metals server log (it logs the resolved PC artifact), or the headless-LSP harness
  (task11) asserting the `__zaozi_marker__` completion appears (behavioral proof the
  patched PC is the loaded one). A direct jar-path probe: query Metals' resolved
  classpath from its workspace/doctor output if exposed; otherwise the behavioral
  marker is the authoritative end-to-end proof.

## 5. Cache-state matrix

| Cache state | Setup | Consumer A (scalac) | Consumer B (Metals PC) |
| --- | --- | --- | --- |
| (a) isolated **patched** cache | point both at `<patched-cache>` | resolves patched jar (hash matches), compiler marker present | PC loads patched jar; `__zaozi_marker__` completion present |
| (b) **empty** isolated cache, offline | empty `COURSIER_CACHE`, `--offline` | **resolution FAILS** (no bytes, nothing to fall back to) | PC resolution FAILS |
| (c) **stock**/un-isolated or online | default `~/.cache/coursier` or online | resolves **stock** jar (hash = stock), marker ABSENT | PC loads stock; no marker (degrades) |

Each asserted: (a) positive hash+marker; (b) expected non-zero exit / resolution
error; (c) hash equals stock + marker absent (graceful degradation, AC-10/AC-4 neg).

## 6. Risks / unknowns for the implementer (task4/task5)

- **Sidecar correctness.** Whether coursier strictly requires the `.checked` +
  `__sha1` sidecars offline, or tolerates jar+pom alone (the ivy-gather'd sealed
  cache appears to carry only jar+pom yet works offline — confirm what minimum
  ivy-gather actually produces vs. the live dev-shell cache, and replicate exactly
  for the patched overlay). Recompute `__sha1`/`__md5` for the patched jar bytes.
- **pom reuse.** The patched jar keeps the stock coordinate/deps, so the stock pom
  is reused verbatim; confirm no pom field encodes the jar hash.
- **Mill compiler resolution path.** Confirm Mill uses the coursier tree (not
  `ivy2local/ivys/ivy.xml`) for `scala3-compiler_3` (strong evidence it does).
- **Metals PC jar-path introspection.** Whether Metals exposes the resolved PC jar
  path directly; if not, rely on the behavioral `__zaozi_marker__` proof.
- **Metals version** (hand-off to task15): the PC artifact must be resolvable and
  loadable by the pinned Metals under nix/JDK 25 before task5's LSP gate.
- **JAVA_TOOL_OPTIONS pollution** (section 3 gotcha) must be neutralized in whatever
  launches the two JVMs.

## Codex cross-check

The `analyze`-routed Codex pass (gpt-5.5, high effort) confirmed the overall
contract and added these concrete refinements (folded into task4/task5 guidance):

1. **Canonical cache root.** `COURSIER_CACHE` must point at the directory whose
   immediate child is `https/` — NOT a parent containing `cache/https` (that is the
   source of the nested `cache/cache/...` ambiguity seen in dev output). After
   copying the nix `shadowCacheDrv`, `COURSIER_CACHE` = the dir directly containing
   `https/`.
2. **Minimum offline set is jar + pom.** For exact `3.8.4` resolution coursier needs
   only the `.jar` and `.pom` at the URL-derived path — no `maven-metadata.xml` /
   `.directory` / version-listing. Sidecars are NOT required in the source
   derivation (the repo's `ivy-gather` output carries jar+pom only and works), but
   **stale sidecars beside a replaced jar are unsafe** → task5 must recompute or
   delete `__sha1`/`__md5`/`.checked` when overlaying patched bytes into a writable
   cache. (Refines §1a: emit jar+pom; only worry about sidecars when mutating a
   writable copy.)
3. **PC is absent from the lock → task4 must ADD it.** `scala3-presentation-compiler_3:3.8.4`
   plus any transitive POM/JAR deps Metals' PC resolution needs must be forced into
   the lock/cache (it is not a zaozi build dep, so mif never captured it).
4. **Provenance scheme (concrete).** Per-jar marker resource
   `META-INF/zaozi-shadow/<org>-<artifact>-3.8.4.properties` (`zaoziShadow=true`,
   `scala3SourceRev`, `patchSet`, `builtBy=nix`) + a `$out/share/zaozi-shadow/hashes.json`
   (whole-jar + marker SHA-256). Behavioral markers **property-gated** by
   `-Dzaozi.shadow.marker=true` (compiler prints one `zaozi-shadow-marker compiler …`
   line; PC adds the `__zaozi_marker__` completion), removable without touching the
   real feature code.
5. **Isolated cache wiring (concrete).** Copy `shadowCacheDrv/cache/.` into a
   writable `$COURSIER_CACHE`; set `COURSIER_CACHE`/`IVY_HOME`/`HOME`/`XDG_CACHE_HOME`
   under an isolated `ZAOZI_SHADOW_ROOT`; Mill uses `COURSIER_REPOSITORIES=ivy2Local|central`
   + a `MILL_JVM_OPTS_PATH` file carrying `-Dcoursier.cache`/`-Dcoursier.ivy.home`/
   `-Divy.home`/`-Duser.home`; Metals uses `COURSIER_REPOSITORIES=central`. The
   wrapper must append shadow `-D`s **last** (or sanitize duplicates) to beat the
   dev-shell hook (§3 gotcha), and must not reuse a running Mill daemon / Metals
   server across cache-state tests.
6. **Strongest provenance probe = actual class-load.** Beyond hashing the
   `mill show zaozi.scalaCompilerClasspath` jar path (strip a `qref:v1:…:` prefix),
   add `-Xlog:class+load=info:file=…` and grep that `dotty.tools.dotc.*` /
   `dotty.tools.pc.ScalaPresentationCompiler` loaded from the
   `scala3-{compiler,presentation-compiler}_3-3.8.4.jar` under `$COURSIER_CACHE`;
   `lsof -p $METALS_PID | rg scala3-presentation-compiler_3-3.8.4.jar` is the Metals
   fallback if class-load logging is unavailable.

Net: §1-§6 above stand; the patched-artifact set must **add the PC + its transitive
deps** to the lock, the marker is the `META-INF/zaozi-shadow` properties +
`-Dzaozi.shadow.marker` scheme, and the authoritative provenance proof is
`-Xlog:class+load` from each real JVM (not a resolver query).
