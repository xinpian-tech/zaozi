#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  zaozi-jasper --top TOP --design FILE [--design FILE ...] [options] [-- runner-options...]

Options:
  --top TOP             Top module to elaborate
  --design FILE         Design RTL file; may be repeated
  --assertion FILE      Assertion/checker RTL file; may be repeated
  --clock EXPR          Jasper clock expression; may be repeated
  --reset EXPR          Jasper reset expression; may be repeated
  --prove-arg ARG       Extra argument passed to `prove -all`; may be repeated
  --out DIR             Output bundle directory, default: out/jasper/TOP
  --force               Replace a non-empty output directory
  --prove, --run        Run JasperGold after generating the bundle
  -h, --help            Show this help

The generated bundle is self-contained and can be rerun with:
  zaozi-jasper-run --bundle DIR
EOF
}

quote() {
  local value="$1"
  printf "'%s'" "${value//\'/\'\"\'\"\'}"
}

reject_space_path() {
  local path="$1"
  if [[ "$path" =~ [[:space:]] ]]; then
    echo "ERROR: Jasper manifest paths may not contain whitespace: $path" >&2
    exit 2
  fi
}

copy_input() {
  local input="$1"
  local subdir="$2"
  local out_dir="$3"
  local abs_dir
  local abs
  local base
  local rel

  if [[ ! -f "$input" ]]; then
    echo "ERROR: input file does not exist: $input" >&2
    exit 2
  fi

  abs_dir="$(cd -- "$(dirname -- "$input")" && pwd -P)"
  base="$(basename -- "$input")"
  reject_space_path "$base"
  abs="$abs_dir/$base"
  rel="$subdir/$base"

  if [[ -e "$out_dir/$rel" ]]; then
    echo "ERROR: duplicate bundle filename: $rel" >&2
    exit 2
  fi

  mkdir -p "$out_dir/$subdir"
  cp "$abs" "$out_dir/$rel"
  printf '%s\n' "$rel"
}

top=""
out_dir=""
force=0
prove=0
designs=()
assertions=()
clocks=()
resets=()
prove_args=()
runner_args=()

while (($#)); do
  case "$1" in
    --top)
      top="${2:?missing --top value}"
      shift 2
      ;;
    --design)
      designs+=("${2:?missing --design value}")
      shift 2
      ;;
    --assertion|--assertions)
      assertions+=("${2:?missing --assertion value}")
      shift 2
      ;;
    --clock)
      clocks+=("${2:?missing --clock value}")
      shift 2
      ;;
    --reset)
      resets+=("${2:?missing --reset value}")
      shift 2
      ;;
    --prove-arg)
      prove_args+=("${2:?missing --prove-arg value}")
      shift 2
      ;;
    --out)
      out_dir="${2:?missing --out value}"
      shift 2
      ;;
    --force)
      force=1
      shift
      ;;
    --prove|--run)
      prove=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      runner_args=("$@")
      break
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$top" ]]; then
  echo "ERROR: --top is required" >&2
  exit 2
fi
if [[ "${#designs[@]}" -eq 0 ]]; then
  echo "ERROR: at least one --design file is required" >&2
  exit 2
fi
reject_space_path "$top"

if [[ -z "$out_dir" ]]; then
  out_dir="$PWD/out/jasper/$top"
fi

if [[ -e "$out_dir" ]] && [[ -n "$(find "$out_dir" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
  if [[ "$force" -eq 0 ]]; then
    echo "ERROR: output directory is not empty: $out_dir" >&2
    echo "       pass --force to replace it" >&2
    exit 2
  fi
  rm -rf "$out_dir"
fi
mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd -P)"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cp "${ZAOZI_JASPER_PROVE_TCL:-$script_dir/jasper_prove.tcl}" "$out_dir/jasper_prove.tcl"
cp "${ZAOZI_JASPER_RUNNER:-$script_dir/run_jasper.sh}" "$out_dir/run_jasper.sh"
chmod +x "$out_dir/run_jasper.sh"

design_files=()
assertion_files=()
for file in "${designs[@]}"; do
  design_files+=("$(copy_input "$file" design "$out_dir")")
done
for file in "${assertions[@]}"; do
  assertion_files+=("$(copy_input "$file" assertions "$out_dir")")
done

{
  printf 'TOP=%s\n' "$(quote "$top")"
  printf 'DESIGN_FILES=%s\n' "$(quote "${design_files[*]}")"
  if [[ "${#assertion_files[@]}" -gt 0 ]]; then
    printf 'ASSERTION_FILES=%s\n' "$(quote "${assertion_files[*]}")"
  fi
  if [[ "${#clocks[@]}" -gt 0 ]]; then
    printf 'CLOCKS=%s\n' "$(quote "${clocks[*]}")"
  fi
  if [[ "${#resets[@]}" -gt 0 ]]; then
    printf 'RESETS=%s\n' "$(quote "${resets[*]}")"
  fi
  if [[ "${#prove_args[@]}" -gt 0 ]]; then
    printf 'PROVE_ARGS=%s\n' "$(quote "${prove_args[*]}")"
  fi
  printf "REPORT_DIR='reports'\n"
} > "$out_dir/manifest.env"

cat > "$out_dir/README.md" <<EOF
# Zaozi JasperGold Bundle

Top: \`$top\`

Run:

\`\`\`sh
./run_jasper.sh --manifest manifest.env
\`\`\`
EOF

echo "Jasper bundle: $out_dir"

if [[ "$prove" -eq 1 ]]; then
  runner="${ZAOZI_JASPER_RUN:-zaozi-jasper-run}"
  "$runner" --bundle "$out_dir" "${runner_args[@]}"
fi
