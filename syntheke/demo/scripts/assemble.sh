#!/usr/bin/env bash
set -euo pipefail

toolchain=$1; source_file=$2; artifacts=$(realpath "$3"); bin=$(realpath -m "$4"); env_file=$(realpath -m "$5")

. "$artifacts/design.env"

work=$(dirname "$bin")/program
rm -rf "$work"
mkdir -p "$work"

"$toolchain/bin/clang" \
  -target riscv32-unknown-elf -march=rv32e -mabi=ilp32e -nostdlib \
  --ld-path="$toolchain/bin/ld.lld" -Wl,-Ttext="$LOAD" \
  "-DUART_BASE=$UART_BASE" "-DLOAD=$LOAD" \
  -o "$work/hello.elf" "$source_file"

"$toolchain/bin/llvm-objcopy" -O binary "$work/hello.elf" "$bin"

symbol() { "$toolchain/bin/llvm-nm" "$work/hello.elf" | awk -v s="$1" '$3 == s { print "0x" $1 }'; }

entry=$(symbol _start)
first_word=$(od -A none -t x4 -N 4 --endian=little "$bin" | tr -d ' ')

cat > "$env_file" <<ENV
HART0_PC=$(symbol done)
HART1_PC=$entry
HART1_FIRST='${entry#0x}: $first_word'
ENV
