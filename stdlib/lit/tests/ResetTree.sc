// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.reset.ResetTree %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir/mixed %t.dir/root
// RUN: %{test} config %t.dir/mixed/config.json --sources '{"name":"event","activeLow":false}' --sources '{"name":"root","activeLow":true}' --sources '{"name":"external","activeLow":true}' --targets '{"name":"mixed","activeLow":true,"links":[{"source":"event","processing":{"$type":"Async","clock":"fast","stages":3}},{"source":"external","processing":{"$type":"Pipeline","clock":"slow","stages":2}}],"processing":{"$type":"Counter","clock":"fast","cycles":3}}' --targets '{"name":"direct","activeLow":false,"links":[{"source":"event"},{"source":"external"}]}' --targets '{"name":"one","activeLow":false,"links":[{"source":"event","processing":{"$type":"Async","clock":"fast","stages":1}}]}' --targets '{"name":"emptyHigh","activeLow":false,"links":[]}' --targets '{"name":"emptyLow","activeLow":true,"links":[]}' --targets '{"name":"counted","activeLow":true,"links":[{"source":"external","processing":{"$type":"Counter","clock":"slow","cycles":2}}],"processing":{"$type":"Async","clock":"fast","stages":2}}' --targets '{"name":"piped","activeLow":true,"links":[{"source":"root"}],"processing":{"$type":"Pipeline","clock":"fast","stages":1}}' --reason '{"clock":"slow","root":"root"}'
// RUN: cd %t.dir/mixed && %{test} design %t.dir/mixed/config.json
// RUN: firld --base-circuit=ResetTree_16ee2c58 %t.dir/mixed/*.mlirbc -o %t.dir/mixed/tree.mlir
// RUN: firtool %t.dir/mixed/tree.mlir --strip-debug-info --lowering-options=disallowLocalVariables | FileCheck %s --check-prefix=TREE
// RUN: %{test} bindings %t.dir/mixed/config.json | FileCheck %s --check-prefix=BINDINGS
// RUN: %{test} config %t.dir/root/config.json --sources '{"name":"root","activeLow":false}' --reason '{"clock":"slow","root":"root"}'
// RUN: cd %t.dir/root && %{test} design %t.dir/root/config.json
// RUN: firld --base-circuit=ResetTree_40dba815 %t.dir/root/*.mlirbc -o %t.dir/root/tree.mlir
// RUN: firtool %t.dir/root/tree.mlir --strip-debug-info --lowering-options=disallowLocalVariables | FileCheck %s --check-prefix=ROOT
// RUN: rm -rf %t.dir

// TREE: target_0_asserted:
// TREE: cover property (@(posedge
// TREE: target_0_released:
// TREE: cover property (@(negedge
// TREE: target_0_test_bypass:
// TREE: cover property (@(posedge ResetTree_16ee2c58.testEnable)
// TREE-LABEL: module ResetTree_16ee2c58(
// TREE: SynchronizedReset_stages3_activeLow cell_0 (
// TREE: .clock (clocks_0),
// TREE: .reset (~sources_0),
// TREE: ResetPipeline_stages2 cell_1 (
// TREE: .clock (clocks_1),
// TREE: .resetN (sources_2),
// TREE: .output_0 ([[PIPE:[a-zA-Z_0-9]+]])
// TREE: ResetCounter_cycles3 cell_2 (
// TREE: .clock (clocks_0),
// TREE: .resetN
// TREE-NEXT: (~((testEnable ? sources_0 : ~_cell_synchronizedReset_0) | ~[[PIPE]])),
// TREE: ResetCounter_cycles1 cell_3 (
// TREE: .clock (clocks_0),
// TREE: .resetN (~sources_0),
// TREE: ResetCounter_cycles2 cell_4 (
// TREE: .clock (clocks_1),
// TREE: .output_0 ([[COUNT:[a-zA-Z_0-9]+]])
// TREE: SynchronizedReset_stages2_activeLow cell_5 (
// TREE: .reset ([[COUNT]]),
// TREE: ResetReason_sources2 cell_7 (
// TREE: .clock (clocks_1),
// TREE: .coldResetN (sources_1),
// TREE: .events_0 (sources_0),
// TREE: .events_1 (~sources_2),
// TREE: assign outputs_3 = 1'h0;
// TREE: assign outputs_4 = 1'h1;

// ROOT-LABEL: module ResetTree_40dba815(
// ROOT: ResetReason_sources1 cell_0 (
// ROOT: .coldResetN (~sources_0),
// ROOT: .events_0 (1'h0),

// BINDINGS: {"sources":[{"name":"event","port":0,"activeLow":false},{"name":"root","port":1,"activeLow":true},{"name":"external","port":2,"activeLow":true}],"clocks":[{"name":"fast","port":0},{"name":"slow","port":1}],"targets":[{"name":"mixed","port":0,"activeLow":true},{"name":"direct","port":1,"activeLow":false},{"name":"one","port":2,"activeLow":false},{"name":"emptyHigh","port":3,"activeLow":false},{"name":"emptyLow","port":4,"activeLow":true},{"name":"counted","port":5,"activeLow":true},{"name":"piped","port":6,"activeLow":true}],"reasons":[{"source":"event","bit":0},{"source":"external","bit":1}]}
