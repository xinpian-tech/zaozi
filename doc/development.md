# Development Documentation

### Building and testing

Enter `nix develop`, then run:

```sh
mill zaozi.compile
mill __.testForked
mill __.lit.tests.run
mill zaozi.benchmark.run 1
```

The build runs Scala 3.8.4 on JDK 25 and links generators with Scala Native.
The flake pins sn-bindgen to upstream main and the Native toolchain to a
revision with foreign-thread callbacks for external MLIR passes.

For a custom installation, point `MLIR_INSTALL_PATH` and `CIRCT_INSTALL_PATH`
at matching prefixes with headers and C API libraries. Set `CLANG`, `CLANGPP`
and `SN_BINDGEN` to their executables. The build uses Python 3 for the generated
by-value C adapters. Tests also use lit, FileCheck, firtool, firld, espresso and
Z3. The pinned CIRCT C header declares a verification-flavor setter missing from
its shared library. The Nix CIRCT prefix builds the adapter in
[nix/pkgs/firtool.cpp](../nix/pkgs/firtool.cpp).

### Writing an MLIR bytecode plugin

```scala
import org.llvm.mlir.raw.{functions as raw}
import org.llvm.mlir.scalalib.{Mlir, ModuleTransform}

object Canonicalize:
  def main(args: Array[String]): Unit =
    val plugin = ModuleTransform(raw.mlirRegisterTransformsPasses()) { module =>
      Mlir.runPipeline(module, "builtin.module(canonicalize)")
    }
    plugin.main(args)
```

A transform declares the dialects used by its input and registers the passes
it needs. The example accepts builtin IR. Read from stdin and redirect stdout
to a file or a second plugin. Diagnostics go to stderr. The framework creates
a context for each invocation, verifies the modified module and copies out its
bytecode before destroying the module and context. Helpers can share operations
while their underlying IR is alive. Keep bytes to use the result in a later
invocation. Use `Mlir.withPassManager` for pipelines assembled in Scala.

`nix build` installs a local Maven repository under `result/share/maven`.
Use it in a downstream Scala Native build, keeping the Scala and Native
versions from [build.mill](../build.mill). The Native artifacts use
`me.jiuyang:mlirlib_native0.5_3:0.0.1-SNAPSHOT` and
`me.jiuyang:zaozi_native0.5_3:0.0.1-SNAPSHOT`. Use `-experimental` for
generator macro annotations. Give clang both installation prefixes' include
and library directories, and make their shared libraries available at run time.
The SemanticDB plugin is a separate JVM compiler JAR.

## IDE Setup
From the development shell, run `mill --bsp-install` in the project root.
Open the workspace with a BSP client such as Metals or IntelliJ IDEA.

### Zed

Install the `scala3-bsp-semantic-ls` Zed adapter through the Nix-managed Zed
configuration, then launch Zed from the project development environment:

```shell
nix develop --command zeditor .
```

Depending on the Zed installation, the command may be named `zed` instead of
`zeditor`. Trust the worktree when Zed prompts for it so project settings and
language servers are enabled. On Linux, the development shell hook generates
the project-level `.zed/settings.json`, selecting the
`scala3-bsp-semantic-ls` adapter and its exact Nix store binary path. Zed does
not discover or download the server. Run the BSP install command above to generate `.bsp/mill-bsp.json`. The Native targets emit SemanticDB for cross-file references and rename. The
Nix-packaged server keeps the zaozi navigation plugin in the JVM presentation
compiler and filters out the Scala Native code-generation plugin there. This
lets hover and go-to-definition resolve nested Bundle fields in unsaved edits.

## Swap CIRCT for development
Zaozi always follows the latest version of CIRCT. To use the specific version
of CIRCT for development, you override the circt version in nix script. You can
also compile CIRCT:
software prerequisite: `cmake`, `clang`, `ninja`, `python3`, `ccache`
Suggest to use two steps flow(compiling MLIR and CIRCT in separate) to speed up
development.
- Firstly compile the MLIR library
```shell
pushd circt/llvm
cmake -G Ninja ../llvm \
  -B build \
  -DCMAKE_BUILD_TYPE=DEBUG \
  -DLLVM_ENABLE_ASSERTIONS=ON \
  -DBUILD_SHARED_LIBS=ON \
  -DLLVM_ENABLE_BINDINGS=OFF \
  -DLLVM_ENABLE_OCAMLDOC=OFF \
  -DLLVM_BUILD_EXAMPLES=OFF \
  -DLLVM_OPTIMIZED_TABLEGEN=ON \
  -DLLVM_ENABLE_PROJECTS=mlir \
  -DLLVM_TARGETS_TO_BUILD=Native
ninja
```
You can skip this if using Nix:
```shell
nix build --no-link --print-out-paths .#mlir-install
```
The output should be configured as `MLIR_INSTALL_PATH`

```shell
cd circt
cmake -G Ninja \
  -B build/debug \
  -DBUILD_SHARED_LIBS=ON \
  -DLLVM_ENABLE_ASSERTIONS=ON \
  -DMLIR_DIR=$MLIR_INSTALL_PATH
ninja -C build
```
configure the `CIRCT_INSTALL_PATH` to `build`, and run tests.

### Debug Flow

Build the relevant target with `nativeLink`, then launch its executable
under gdb or lldb. For example, use `gdb out/zaozi/opt/nativeLink.dest/out`
to set a breakpoint in a pass and run the pipeline with bytecode redirected
from a file.
