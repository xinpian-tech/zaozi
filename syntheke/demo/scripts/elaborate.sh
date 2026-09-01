#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# elaborate.sh <repo-root> <output-dir> [config.json]
#
# Mill's whole job: build the demo jar. Then run it, and everything the design implies lands in the output directory —
# the Verilog, the linked bytecode, the program, the debugger's target description and the tooling exports.
set -euo pipefail

root=$1
# Resolved before the cd below: meson names its outputs relative to the build directory.
out=$(realpath -m "$2")
config=${3:-}
[ -n "$config" ] && config=$(realpath "$config")

# Mill bootstraps a JDK of its own if run from anywhere else.
cd "$root"
# $MILL may carry flags of its own, so it is a command line, not a program name.
read -r -a mill <<< "${MILL:-mill}"
"${mill[@]}" syntheke.demo.assembly > /dev/null
# `show` answers with a PathRef — "ref:v0:<hash>:/path/to/out.jar".
jar=$("${mill[@]}" --ticker false show syntheke.demo.assembly | tr -d '"' | sed 's|^ref:[^/]*||')

libs=""
[ -n "${MLIR_INSTALL_PATH:-}" ] && libs="$MLIR_INSTALL_PATH/lib"
[ -n "${CIRCT_INSTALL_PATH:-}" ] && libs="$libs:$CIRCT_INSTALL_PATH/lib"

rm -rf "$out"
mkdir -p "$out" "$out.mlirbc"

# zaozi dumps one .mlirbc per module for the linker to resolve; that is scratch, not an artifact.
# -XX:TieredStopAtLevel=1: C2 miscompiles this workload and takes the JVM down with it (and elaboration is too short
# to pay for C2 anyway) — see syntheke/package.mill.
ZAOZI_OUTDIR="$out.mlirbc" \
  java -Xss32m -XX:TieredStopAtLevel=1 --enable-native-access=ALL-UNNAMED \
    -Djava.library.path="$libs" -jar "$jar" "$out" ${config:+"$config"}
