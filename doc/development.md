# Development Documentation

The Zaozi project is a Scala project that links to CIRCT with the
[Project Panama](https://openjdk.org/projects/panama/).

## Development Flow
### Nix Flow

Zaozi by default use the nix flow to help you to get anything done for zaozi 
development in linux or darwin.

### Non-Nix Flow
For the development reason, e.g. swap to a debug version of CIRCT, or don't
want to use nix for development. You need to prepare such dependencies:
- [mill](https://mill-build.org) for build system in env.
- [JDK 25](https://openjdk.org/projects/jdk/25/) for setup Java environment.
  zaozi compiles with `--release 25` (classfile major version 69), so JDK 25
  is the minimum runtime and the only supported development JDK.
- [jextract](https://github.com/openjdk/jextract) provided by `JEXTRACT_INSTALL_PATH`
- [CIRCT](https://github.com/llvm/circt/) provided by `CIRCT_INSTALL_PATH`
- [MLIR](https://github.com/llvm/circt/) provided by `MLIR_INSTALL_PATH`,
  version of MLIR should match to CIRCT.
- [Lit](https://llvm.org/docs/CommandGuide/lit.html) provided by 
  `LIT_INSTALL_PATH` for running lit tests under zaozi.

The version of the dependencies are managed by nix, since CI only checks with
Nix, users should use version that provided by Nix.

## IDE Setup
You can use `nix develop` to enter the development environment in the Nix flow,
after you installed the dependencies and set the env. You can use
`mill mill.bsp.BSP/install` to config BSP and open with Intellij IDEA or metal.

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

It requires using lldb or gdb to attach to the jvm subprocess for debugging

- Firstly add
```C
#include <csignal>
#include <iostream>
#include <bits/signum-arch.h>
#include <unistd.h>

...
  std::cerr << "Current PID: " << getpid() << std::endl;
  raise(SIGSTOP);
```
to the entrance of your library, for example `populatePreprocessTransforms`,
then run test which will stack. Now you can attach gdb/lldb to this PID and send
`SIGCONT` to start debug.
