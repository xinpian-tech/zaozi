// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.clock.ClockTree %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/config.json --inputs a --inputs b --targets '{"name":"out","links":[{"source":"a","path":{"gate":{"positive":true,"clockDuringReset":false},"divider":{"width":4,"initial":"2","clockDuringReset":false,"automatic":false},"invert":true,"gateGuide":{"cell":"Guide","input":"I","output":"Z","instance":"link_gate_guide"},"dividerGuide":{"cell":"Guide","input":"I","output":"Z","instance":"link_divider_guide"},"inverterGuide":{"cell":"Guide","input":"I","output":"Z","instance":"link_inverter_guide"}}},{"source":"b"}],"selection":"Raw","muxGuide":{"cell":"Guide","input":"I","output":"Z","instance":"mux_guide"},"path":{"gate":{"positive":true,"clockDuringReset":false},"divider":{"width":4,"initial":"2","clockDuringReset":false,"automatic":false},"invert":true,"gateGuide":{"cell":"Guide","input":"I","output":"Z","instance":"target_gate_guide"},"dividerGuide":{"cell":"Guide","input":"I","output":"Z","instance":"target_divider_guide"},"inverterGuide":{"cell":"OutputGuide","input":"CLK","output":"Y"}}}'
// RUN: cd %t.dir && %{test} design %t.dir/config.json
// RUN: firld --base-circuit=$(basename %t.dir/ClockTree_*.mlirbc .mlirbc) %t.dir/*.mlirbc -o %t.dir/tree.mlir
// RUN: firtool %t.dir/tree.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv --lowering-options=disallowLocalVariables -o %t.dir/tree.sv
// RUN: FileCheck %s --input-file=%t.dir/tree.sv
// RUN: rm -rf %t.dir

// CHECK-LABEL: module ClockTree_{{[a-f0-9]+}}(
// CHECK: ClockGate_positivetrue_clockDuringResetfalse target_0_link_0_gate (
// CHECK: .clock (inputs_0),
// CHECK: .output_0 ([[LG:[a-zA-Z_0-9]+]])
// CHECK: Guide link_gate_guide (
// CHECK: .I ([[LG]]),
// CHECK: .Z ([[LGG:[a-zA-Z_0-9]+]])
// CHECK: ClockDivider_{{[a-f0-9]+}} target_0_link_0_divider (
// CHECK: .clock ([[LGG]]),
// CHECK: .output_0 ([[LD:[a-zA-Z_0-9]+]]),
// CHECK: Guide link_divider_guide (
// CHECK: .I ([[LD]]),
// CHECK: .Z ([[LDG:[a-zA-Z_0-9]+]])
// CHECK: ZaoziClockInverter target_0_link_0_inverter (
// CHECK: .a ([[LDG]]),
// CHECK: .outClock ([[LI:[a-zA-Z_0-9]+]])
// CHECK: Guide link_inverter_guide (
// CHECK: .I ([[LI]]),
// CHECK: .Z ([[LIG:[a-zA-Z_0-9]+]])
// CHECK: ZaoziClockMux target_0_mux_0 (
// CHECK: .a (1'h0),
// CHECK: .b ([[LIG]]),
// CHECK: .outClock ([[M0:[a-zA-Z_0-9]+]])
// CHECK: ZaoziClockMux target_0_mux_1 (
// CHECK: .a ([[M0]]),
// CHECK: .b (inputs_1),
// CHECK: .outClock ([[M1:[a-zA-Z_0-9]+]])
// CHECK: Guide mux_guide (
// CHECK: .I ([[M1]]),
// CHECK: .Z ([[MG:[a-zA-Z_0-9]+]])
// CHECK: ClockGate_positivetrue_clockDuringResetfalse target_0_gate (
// CHECK: .clock ([[MG]]),
// CHECK: .output_0 ([[TG:[a-zA-Z_0-9]+]])
// CHECK: Guide target_gate_guide (
// CHECK: .I ([[TG]]),
// CHECK: .Z ([[TGG:[a-zA-Z_0-9]+]])
// CHECK: ClockDivider_{{[a-f0-9]+}} target_0_divider (
// CHECK: .clock ([[TGG]]),
// CHECK: .output_0 ([[TD:[a-zA-Z_0-9]+]]),
// CHECK: Guide target_divider_guide (
// CHECK: .I ([[TD]]),
// CHECK: .Z ([[TDG:[a-zA-Z_0-9]+]])
// CHECK: ZaoziClockInverter target_0_inverter (
// CHECK: .a ([[TDG]]),
// CHECK: .outClock ([[TI:[a-zA-Z_0-9]+]])
// CHECK: OutputGuide target_0_inverter_guide (
// CHECK: .CLK ([[TI]]),
// CHECK: .Y (outputs_0)
