// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.clock.ClockTree %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir/raw %t.dir/mixed
// RUN: %{test} config %t.dir/raw/config.json --inputs a --inputs b --inputs c --targets '{"name":"fanout","links":[{"source":"selected"}],"path":{"invert":true}}' --targets '{"name":"selected","links":[{"source":"a","path":{"invert":true}},{"source":"b"},{"source":"c"}],"selection":"Raw"}'
// RUN: cd %t.dir/raw && %{test} design %t.dir/raw/config.json
// RUN: firld --base-circuit=$(basename %t.dir/raw/ClockTree_*.mlirbc .mlirbc) %t.dir/raw/*.mlirbc -o %t.dir/raw/tree.mlir
// RUN: firtool %t.dir/raw/tree.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv --lowering-options=disallowLocalVariables | FileCheck %s --check-prefix=RAW
// RUN: %{test} config %t.dir/mixed/config.json --inputs a --inputs b --inputs c --targets '{"name":"fanout","links":[{"source":"selected"}],"path":{"invert":true}}' --targets '{"name":"selected","links":[{"source":"a","path":{"invert":true}},{"source":"b"},{"source":"divided"}],"selection":"Raw"}' --targets '{"name":"divided","links":[{"source":"a","path":{"gate":{"positive":true,"clockDuringReset":false},"divider":{"width":4,"initial":"3","clockDuringReset":false,"automatic":false},"invert":true}}],"path":{"gate":{"positive":false,"clockDuringReset":true},"divider":{"width":4,"initial":"2","clockDuringReset":false,"automatic":false},"invert":true}}' --targets '{"name":"safe","links":[{"source":"b"},{"source":"c"}],"selection":{"$type":"GlitchFree","stages":2,"clockDuringReset":false},"path":{"divider":{"width":4,"initial":"1","clockDuringReset":false,"automatic":true},"invert":true}}'
// RUN: cd %t.dir/mixed && %{test} design %t.dir/mixed/config.json
// RUN: firld --base-circuit=$(basename %t.dir/mixed/ClockTree_*.mlirbc .mlirbc) %t.dir/mixed/*.mlirbc -o %t.dir/mixed/tree.mlir
// RUN: firtool %t.dir/mixed/tree.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv --lowering-options=disallowLocalVariables | FileCheck %s --check-prefix=MIXED
// RUN: %{test} bindings %t.dir/raw/config.json | FileCheck %s --check-prefix=BINDINGS
// RUN: rm -rf %t.dir

// RAW: target_1_select_0:
// RAW: target_1_select_1:
// RAW: target_1_select_2:
// RAW: target_1_invalid_selection:
// RAW-LABEL: module ClockTree_{{[a-f0-9]+}}(
// RAW: ZaoziClockInverter target_1_link_0_inverter (
// RAW: .a (inputs_0),
// RAW: .outClock ([[INVERTED:[a-zA-Z_0-9]+]])
// RAW: ZaoziClockMux target_1_mux_0 (
// RAW: .a (1'h0),
// RAW: .b ([[INVERTED]]),
// RAW: .outClock ([[M0:[a-zA-Z_0-9]+]])
// RAW: ZaoziClockMux target_1_mux_1 (
// RAW: .a ([[M0]]),
// RAW: .b (inputs_1),
// RAW: .outClock ([[M1:[a-zA-Z_0-9]+]])
// RAW: ZaoziClockMux target_1_mux_2 (
// RAW: .a ([[M1]]),
// RAW: .b (inputs_2),
// RAW: .outClock ([[SELECTED:[a-zA-Z_0-9]+]])
// RAW: ZaoziClockInverter target_0_inverter (
// RAW: .a ([[SELECTED]]),
// RAW: .outClock (outputs_0)
// RAW: assign outputs_1 = [[SELECTED]];

// MIXED-LABEL: module ClockTree_{{[a-f0-9]+}}(
// MIXED: ClockGate_positivetrue_clockDuringResetfalse target_2_link_0_gate (
// MIXED: .clock (inputs_0),
// MIXED: .output_0 ([[LINK_GATE:_[a-z0-9_]+]])
// MIXED: ClockDivider_{{[a-f0-9]+}} target_2_link_0_divider (
// MIXED: .clock ([[LINK_GATE]]),
// MIXED: .valid (controls_target_2_link_0_divider_valid),
// MIXED: .ready (controls_target_2_link_0_divider_ready),
// MIXED: .output_0 ([[LINK_DIV:_[a-z0-9_]+]]),
// MIXED: ZaoziClockInverter target_2_link_0_inverter (
// MIXED: .a ([[LINK_DIV]]),
// MIXED: .outClock ([[INVERTED:[a-zA-Z_0-9]+]])
// MIXED: ClockGate_positivefalse_clockDuringResettrue target_2_gate (
// MIXED: .clock ([[INVERTED]]),
// MIXED: .output_0 ([[TARGET_GATE:_[a-z0-9_]+]])
// MIXED: ClockDivider_{{[a-f0-9]+}} target_2_divider (
// MIXED: .clock ([[TARGET_GATE]]),
// MIXED: .divisor (controls_target_2_path_divider_divisor),
// MIXED: .valid (controls_target_2_path_divider_valid),
// MIXED: .ready (controls_target_2_path_divider_ready),
// MIXED: ClockMux_ca1fcf5a target_3_mux (
// MIXED: .clocks_0 (inputs_1),
// MIXED: .clocks_1 (inputs_2),
// MIXED: .testClock (controls_target_3_testClock),
// MIXED: .output_0 ([[SELECTED:_[a-z0-9_]+]])
// MIXED: ClockDivider_{{[a-f0-9]+}} target_3_divider (
// MIXED: .clock ([[SELECTED]]),
// MIXED: .output_0 ([[AUTOMATIC:_[a-z0-9_]+]]),
// MIXED: ZaoziClockInverter target_3_inverter (
// MIXED: .a ([[AUTOMATIC]]),
// MIXED: .outClock (outputs_3)

// BINDINGS: {"inputs":[{"name":"a","port":0},{"name":"b","port":1},{"name":"c","port":2}],"targets":[{"name":"fanout","port":0,"sources":["selected"]},{"name":"selected","port":1,"sources":["a","b","c"]}]}
