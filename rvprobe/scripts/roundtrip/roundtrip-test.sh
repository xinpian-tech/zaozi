#!/usr/bin/env bash
# roundtrip-test.sh — Round-trip test for asm2dsl.py correctness.
#
# Validates: original.S → asm2dsl.py → DSL.scala → mill runMain → output.S
#            → compile both → compare objdumps
#
# Usage:
#   roundtrip-test.sh [file.S ...]
#   No args → runs all .S files in rvprobe/src/cases/output/asm/privilege/
#   With args → runs specified .S files only

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ASM2DSL="$REPO_ROOT/rvprobe/scripts/asm2dsl.py"
WRAP_DSL="$SCRIPT_DIR/wrap_dsl.py"
COMPARE="$SCRIPT_DIR/compare_objdump.py"
LINKER_SCRIPT="$SCRIPT_DIR/baremetal.ld"
PROBES_DIR="$REPO_ROOT/rvprobe/src/cases/output/asm/privilege"
GENERATED_DIR="$REPO_ROOT/rvprobe/src/cases/privilege"

CC="riscv64-unknown-linux-gnu-gcc"
OBJDUMP="riscv64-unknown-linux-gnu-objdump"
MARCH="rv64gc_zfa"
CC_FLAGS="-nostdlib -nostartfiles -march=$MARCH -mabi=lp64d -T $LINKER_SCRIPT"

# Colors (if terminal supports it)
if [ -t 1 ]; then
  GREEN='\033[0;32m'
  RED='\033[0;31m'
  YELLOW='\033[0;33m'
  NC='\033[0m'
else
  GREEN='' RED='' YELLOW='' NC=''
fi

pass_count=0
fail_count=0
skip_count=0

run_test() {
  local input_s="$1"
  local basename
  basename="$(basename "$input_s" .S)"

  # Capitalize first letter for Scala class name
  local class_name
  class_name="$(echo "${basename:0:1}" | tr '[:lower:]' '[:upper:]')${basename:1}Gen"

  local work_dir
  work_dir="$(mktemp -d)"
  trap "rm -rf '$work_dir'" RETURN

  local gen_scala="$GENERATED_DIR/${class_name}.scala"
  local output_s="$work_dir/output.S"

  echo -n "Testing $basename... "

  # Step 1: asm2dsl.py → wrap_dsl.py → generated .scala
  if ! python3 "$ASM2DSL" "$input_s" | python3 "$WRAP_DSL" --name "$class_name" > "$gen_scala" 2>"$work_dir/asm2dsl.err"; then
    echo -e "${RED}FAIL${NC} (asm2dsl/wrap_dsl failed)"
    cat "$work_dir/asm2dsl.err" >&2
    rm -f "$gen_scala"
    fail_count=$((fail_count + 1))
    return
  fi

  # Step 2: mill runMain to generate output.S
  if ! mill -i rvprobe.runMain "me.jiuyang.rvprobe.cases.privilege.$class_name" "$output_s" >"$work_dir/mill.out" 2>&1; then
    echo -e "${RED}FAIL${NC} (mill runMain failed)"
    cat "$work_dir/mill.out" >&2
    rm -f "$gen_scala"
    fail_count=$((fail_count + 1))
    return
  fi

  # Clean up generated scala file
  rm -f "$gen_scala"

  # Step 3: Compile both .S files
  if ! $CC $CC_FLAGS -o "$work_dir/original.elf" "$input_s" 2>"$work_dir/cc_orig.err"; then
    echo -e "${YELLOW}SKIP${NC} (original.S failed to compile)"
    cat "$work_dir/cc_orig.err" >&2
    skip_count=$((skip_count + 1))
    return
  fi

  if ! $CC $CC_FLAGS -o "$work_dir/output.elf" "$output_s" 2>"$work_dir/cc_out.err"; then
    echo -e "${RED}FAIL${NC} (output.S failed to compile)"
    cat "$work_dir/cc_out.err" >&2
    fail_count=$((fail_count + 1))
    return
  fi

  # Step 4: objdump both
  $OBJDUMP -d "$work_dir/original.elf" > "$work_dir/original.dump"
  $OBJDUMP -d "$work_dir/output.elf" > "$work_dir/output.dump"

  # Step 5: Compare
  if python3 "$COMPARE" "$work_dir/original.dump" "$work_dir/output.dump"; then
    echo -e "${GREEN}PASS${NC}"
    pass_count=$((pass_count + 1))
  else
    echo -e "${RED}FAIL${NC}"
    fail_count=$((fail_count + 1))
  fi
}

# Collect test files
if [ $# -gt 0 ]; then
  test_files=("$@")
else
  test_files=()
  for f in "$PROBES_DIR"/*.S; do
    [ -f "$f" ] && test_files+=("$f")
  done
fi

if [ ${#test_files[@]} -eq 0 ]; then
  echo "No .S test files found."
  exit 1
fi

echo "=== Round-trip test: ${#test_files[@]} file(s) ==="
echo ""

for f in "${test_files[@]}"; do
  run_test "$f"
done

echo ""
echo "=== Results: ${pass_count} passed, ${fail_count} failed, ${skip_count} skipped ==="

if [ "$fail_count" -gt 0 ]; then
  exit 1
fi
