#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  run_jasper.sh [--bundle DIR] [--manifest FILE] [--script FILE] [--workdir DIR] [--keep-workdir]

Environment or manifest variables:
  TOP              top module to elaborate
  DESIGN_FILES     whitespace-separated RTL files
  ASSERTION_FILES  whitespace-separated assertion/checker files
  REPORT_DIR       report directory, default: reports
  CLOCKS           optional whitespace-separated Jasper clock expressions
  RESETS           optional whitespace-separated Jasper reset expressions
  PROVE_ARGS       optional extra arguments for `prove -all`
  JASPER_BIN       JasperGold executable, default: jaspergold, then jg

If --bundle is used, the bundle is copied to a writable temporary workdir before
JasperGold runs, matching the staged-workdir pattern used by the DWBB flow.
EOF
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
script="$SCRIPT_DIR/jasper_prove.tcl"
bundle=""
manifest="manifest.env"
workdir=""
keep_workdir=0

while (($#)); do
  case "$1" in
    --bundle)
      bundle="${2:?missing --bundle value}"
      shift 2
      ;;
    --manifest)
      manifest="${2:?missing --manifest value}"
      shift 2
      ;;
    --script)
      script="${2:?missing --script value}"
      shift 2
      ;;
    --workdir)
      workdir="${2:?missing --workdir value}"
      shift 2
      ;;
    --keep-workdir)
      keep_workdir=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -n "$bundle" ]]; then
  if [[ -z "$workdir" ]]; then
    workdir="$(mktemp -d -t zaozi-jasper.XXXXXX)"
    if [[ "$keep_workdir" -eq 0 ]]; then
      trap 'rm -rf "$workdir"' EXIT
    fi
  else
    mkdir -p "$workdir"
  fi
  cp -rL "$bundle"/. "$workdir/"
  chmod -R u+w "$workdir"
else
  workdir="${workdir:-$PWD}"
fi

cd "$workdir"

if [[ -f "$manifest" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$manifest"
  set +a
fi

: "${TOP:?TOP is required}"
: "${DESIGN_FILES:?DESIGN_FILES is required}"

REPORT_DIR="${REPORT_DIR:-reports}"
mkdir -p "$REPORT_DIR"

if [[ -z "${JASPER_BIN:-}" ]]; then
  if command -v jaspergold >/dev/null 2>&1; then
    JASPER_BIN=jaspergold
  elif command -v jg >/dev/null 2>&1; then
    JASPER_BIN=jg
  else
    echo "ERROR: JasperGold executable not found; set JASPER_BIN" >&2
    exit 127
  fi
fi

export TOP DESIGN_FILES ASSERTION_FILES="${ASSERTION_FILES:-}" REPORT_DIR
export CLOCKS="${CLOCKS:-}" RESETS="${RESETS:-}" PROVE_ARGS="${PROVE_ARGS:-}"

echo "=== JasperGold proof ==="
echo "Workdir: $workdir"
echo "Top: $TOP"
echo "Design files: $DESIGN_FILES"
echo "Assertion files: ${ASSERTION_FILES:-<none>}"
echo

"$JASPER_BIN" -batch -tcl "$script" 2>&1 | tee "$REPORT_DIR/jasper_stdout.log"
