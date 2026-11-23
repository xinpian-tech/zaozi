# Zaozi

Zaozi is an experimental project aimed at rewriting Chisel in pure Scala 3.
It will not replace Chisel, but provide a minimized solution to create an plugable eDSL in Scala 3.
The goal of this project is providing an eDSL frontend framework for hardware designs. 

## Requirements

Zaozi generators are Scala Native executables linked to MLIR and CIRCT. Enter
`nix develop` for Scala 3.8.4, Mill, sn-bindgen, clang, the matching native
libraries, firtool and the test tools. JDK 25 runs the build, macros and IDE
compiler plugin.

```sh
nix build
result/bin/zaozi-opt 'builtin.module(canonicalize)' < input.mlirbc > output.mlirbc
```

The build installs the Scala libraries in `result/share/maven`. See the
[development guide](doc/development.md) for downstream generator and plugin builds.

When the Mill toolchain or its dependencies change, regenerate the locked Maven
repository from the project root:

```bash
nix develop -c ./bump-mill-lock.sh
```

## Project Structure

- **mlirlib** provides the Scala Native MLIR API, managed transform entry points, and a generated C binding.
- **circtlib** builds on the same MLIR handles and provides CIRCT dialects, operations, FIRRTL and firtool integration.
- **zaozi** provides the hardware eDSL, generator macros and module elaboration.
- **zaozi-compiler-plugin** exposes dynamic hardware fields to SemanticDB and the presentation compiler.

## Design Philosophy

Zaozi emphasizes a modular and minimalistic approach, delegating build processes to MLIR via C-API.

Elaboration builds MLIR operations directly. Within a transform, Scala code works with live IR. Independent transforms exchange MLIR bytecode.

Additionally, Zaozi separates the eDSL API declaration with Scala 3 `given` pattern, supporting swap APIs implementation through `given` a type class.

## Licensing

The project is licensed under the Apache License 2.0.

© Jiuyang Liu <liu@jiuyang.me>. All Rights Reserved.
