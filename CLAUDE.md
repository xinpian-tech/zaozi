# CLAUDE.md

## Build System

Mill build system with Scala 3.6.2 and JDK 21 (preview features enabled).

```bash
mill <module>.compile          # compile a single module
mill __.compile                # compile everything
mill <module>.tests            # run unit tests (UTest framework), e.g. mill smtlib.tests
mill <module>.lit.run           # run Lit integration tests (zaozi, stdlib)
mill <module>.benchmark.runJmh # run JMH benchmarks
mill <module>.reformat         # scalafmt on one module
mill __.reformat               # scalafmt on all modules
mill <module>.fix              # scalafix on one module
mill mill.bsp.BSP/install      # IDE setup (BSP)
```

## Version Control

This project uses **Jujutsu (jj)** for version control. Use `jj` commands instead of `git`.

**Workflow:**
1. `jj new` — start a new working change before beginning work
2. Make changes
3. `jj describe -m "short description"` — describe the completed work

## Current Focus

Active development is on the **rvprobe** module (RISC-V instruction generation via two-stage SMT constraint solving).

## Environment Setup

**Nix (recommended):** `nix develop` or use direnv with the `.envrc`.

**Non-nix:** Install JDK 21, mill, jextract, and set:
- `CIRCT_INSTALL_PATH` — path to CIRCT install
- `MLIR_INSTALL_PATH` — path to MLIR install
- `LIT_INSTALL_PATH` — path to Lit

JVM requires: `--enable-native-access=ALL-UNNAMED --enable-preview`

## Architecture

Zaozi is a Scala 3 hardware eDSL (rewrite of Chisel concepts) backed by MLIR/CIRCT via Project Panama FFI. There is no local AST — MLIR values are bound directly to Scala values through the C-API. Modules are constructed without base class extension.

### Modules

- **mlirlib** / **circtlib** — Java Panama wrappers around MLIR and CIRCT C-APIs (generated via jextract)
- **zaozi** — Core DSL: type system (`valuetpe/`, `reftpe/`), default API impls (`default/`), macros (`magic/`). Uses Scala 3 `given` type classes to separate API declaration from implementation
- **stdlib** — Standard hardware library (Queue, Decoupled, BitSet, etc.)
- **smtlib** — SMT-LIB support for constraint solving
- **decoder** — Bit pattern matching and truth tables for instruction decoding
- **rvdecoderdb** — RISC-V instruction database parser
- **rvprobe** — RISC-V instruction generation via two-stage SMT constraint solving
- **testlib** — Test utilities (ZaoziTest base class)
- **omlib** — Object Model value definitions

Each module has its own `package.mill` for build config.

## Code Style

- scalafmt v3.9.7: max 120 columns, `align.preset = most`
- scalafix: OrganizeImports (grouped: scala → me.jiuyang → org.llvm → *), ExplicitResultTypes (implicits only), RemoveUnused
