#!/usr/bin/env bash
# verilate.sh <artifacts-dir> <sim-dir> <dpi-prefix> <output-binary> <model>...
#
# Builds the testbench simulator. The design is the testbench unit's file set with its DV layer bound in; the
# Verification layer stays out, since verilator does not parse its SVA. The behavioural definitions of the external
# modules come from the source tree, and what they do beyond driving pins is in the DPI library.
set -euo pipefail

artifacts=$(realpath "$1"); sim=$2; dpi=$3; out=$(realpath -m "$4")
shift 4

work=$(dirname "$out")/verilate
rm -rf "$work"
mkdir -p "$work"
grep -v '_Verification\.sv$' "$artifacts/Testbench/filelist.f" > "$work/filelist.f"

models=()
for m in "$@"; do models+=("$sim/$m"); done

# The file lists name their files relative to the artifacts directory.
cd "$artifacts"
verilator --binary --timing -Wno-fatal -j 4 \
  --top-module Testbench --Mdir "$work" \
  -LDFLAGS "-L$dpi/lib -lsyntheke_dpi -Wl,-rpath,$dpi/lib" \
  -f "$work/filelist.f" -f Testbench/layers-DV.f "${models[@]}" > "$work/verilator.log" 2>&1 ||
  { cat "$work/verilator.log" >&2; exit 1; }

cp "$work/VTestbench" "$out"
