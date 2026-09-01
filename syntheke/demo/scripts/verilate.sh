#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# verilate.sh <verilator> <artifacts-dir> <sim-dir> <dpi-prefix> <output-binary> <model>...
#
# The design is a file set: firtool's `filelist.f` is the release build, the `layers-*.sv` collateral binds the trace
# in on top of it, and the behavioral definitions of the external modules come from the source tree. What those
# modules actually do is in the DPI library, which the linker resolves their imports against.
set -euo pipefail

verilator=$1; artifacts=$(realpath "$2"); sim=$3; dpi=$4; out=$(realpath -m "$5")
shift 5

models=()
for m in "$@"; do models+=("$sim/$m"); done

work=$(dirname "$out")/verilate
rm -rf "$work"
mkdir -p "$work"

# `filelist.f` names its files bare, so verilator resolves them from here.
cd "$artifacts"
"$verilator" --binary --timing -Wno-fatal -j 0 \
  --top-module Top --Mdir "$work" \
  -LDFLAGS "-L$dpi/lib -lsyntheke_dpi -Wl,-rpath,$dpi/lib" \
  -f filelist.f layers-*.sv "${models[@]}"

cp "$work/VTop" "$out"
