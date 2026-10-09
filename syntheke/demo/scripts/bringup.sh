#!/usr/bin/env bash
# bringup.sh <simulator> <simprobe> <artifacts-dir> <program.bin> <program.env> <dram-config>
#
# A bring-up: power on a chip that halts out of reset with nothing in memory, attach a debugger over JTAG, power-cycle
# CPU1 through the PRCM, download the program, give each hart its start PC, let them go, and read the UART.
#
# Nothing about the design is written here: `design.env` comes out of the elaboration and `program.env` out of the
# program's symbol table.
set -euo pipefail

# Resolved before the cd below: meson names its outputs relative to the build directory.
simulator=$(realpath "$1"); simprobe=$2; artifacts=$(realpath "$3")
program=$(realpath "$4"); program_env=$(realpath "$5"); dram_config=$6

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"

# shellcheck source=/dev/null
. "$artifacts/design.env"
# shellcheck source=/dev/null
. "$program_env"
cp "$artifacts/target.yaml" .
cp "$program" program.bin
# The memory model's CONFIG parameter names it, and finds it beside the simulation.
cp "$dram_config" dram.yaml

"$simulator" > sim.out 2> sim.err &
sim=$!

fail() { echo "$1" >&2; echo "--- simulation ---" >&2; cat sim.out sim.err >&2; exit 1; }

"$simprobe" \
  --bridge "$JTAG_BRIDGE" --target target.yaml --chip "$CHIP" \
  --image program.bin --load "$LOAD" \
  --hart-pc "0:$HART0_PC" --hart-pc "1:$HART1_PC" --power-base "$POWER_BASE" ||
  { kill "$sim" 2>/dev/null || true; fail 'the debugger failed'; }

# The design ends its own run: the console stops the simulation at the newline.
wait "$sim" || true

grep -q 'hello world' sim.out || fail 'the UART never printed'
# The trace reached the testbench through the probes: hart 1 ran the program from its first instruction, hart 0 sat
# in the done-spin.
head -n 1 trace-dut.soc.core1.log | grep -q "^$HART1_FIRST" || fail "hart 1 did not start at $HART1_FIRST"
grep -qv "^${HART0_PC#0x}: " trace-dut.soc.core0.log && fail 'hart 0 left the done-spin'

echo "brought up: $(grep -c . trace-dut.soc.core1.log) instructions on hart 1, and the UART printed"
