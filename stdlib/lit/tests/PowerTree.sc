// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.power.PowerTree %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/config.json --domains '{"name":"consumer","parameter":{"hasSwitch":true,"waitDependencyCycles":"3","settleOnCycles":"2","settleOffCycles":"4"},"dependencies":[{"source":"root","kind":"Hard"},{"source":"aon","kind":"Soft"}],"follows":[{"name":"consumerReset","clock":"slow","stages":3},{"name":"oneReset","clock":"fast","stages":1}]}' --domains '{"name":"root","parameter":{"hasSwitch":true,"waitDependencyCycles":"0","settleOnCycles":"0","settleOffCycles":"2"},"follows":[{"name":"rootReset","clock":"fast","stages":2}]}' --domains '{"name":"aon","parameter":{"hasSwitch":false,"waitDependencyCycles":"0","settleOnCycles":"1","settleOffCycles":"0"},"follows":[{"name":"aonReset","clock":"slow","stages":2}]}'
// RUN: cd %t.dir && %{test} design %t.dir/config.json
// RUN: firld --base-circuit=PowerTree_9eefa1cc %t.dir/*.mlirbc -o %t.dir/tree.mlir
// RUN: firtool %t.dir/tree.mlir --strip-debug-info --lowering-options=disallowLocalVariables | FileCheck %s --check-prefix=TREE
// RUN: %{test} bindings %t.dir/config.json | FileCheck %s --check-prefix=BINDINGS
// RUN: rm -rf %t.dir

// TREE: domain_0_reset_0_released:
// TREE: domain_0_reset_1_released:
// TREE: domain_1_reset_0_released:
// TREE: domain_2_reset_0_released:
// TREE-LABEL: module PowerTree_9eefa1cc(
// TREE: wire resetN_0 = resetN & _cell_resetGateN_1;
// TREE: wire released_layerCapture = testEnable | _receiver_synchronizedReset_1;
// TREE: wire released_layerCapture_0 = testEnable | _receiver_output;
// TREE: PowerControl_427afa66 cell_0 (
// TREE: .hardReady ([[ROOT_READY:_[a-z0-9_]+]]),
// TREE: .softReady ([[AON_READY:_[a-z0-9_]+]]),
// TREE: PowerControl_110b8e45 cell_1 (
// TREE: .ready ([[ROOT_READY]]),
// TREE: PowerControl_325e0ca8 cell_2 (
// TREE: .enable (1'h1),
// TREE: .faultClear (1'h0),
// TREE: .ready ([[AON_READY]]),
// TREE: SynchronizedReset_stages3_activeLow receiver (
// TREE: .clock (clocks_0),
// TREE: .reset (resetN_0),
// TREE: ResetCounter_cycles1 receiver_0 (
// TREE: .clock (clocks_1),
// TREE: .resetN (resetN_0),
// TREE: .testEnable (1'h0),
// TREE: SynchronizedReset_stages2_activeLow receiver_1 (
// TREE: .clock (clocks_1),
// TREE: SynchronizedReset_stages2_activeLow receiver_2 (
// TREE: .clock (clocks_0),

// BINDINGS: {"clocks":[{"name":"slow","port":0},{"name":"fast","port":1}],"domains":[{"name":"consumer","port":0,"kind":"switched","dependencies":[{"source":"root","kind":"Hard"},{"source":"aon","kind":"Soft"}],"resets":[{"name":"consumerReset","port":0,"clock":"slow"},{"name":"oneReset","port":1,"clock":"fast"}]},{"name":"root","port":1,"kind":"root","dependencies":[],"resets":[{"name":"rootReset","port":0,"clock":"fast"}]},{"name":"aon","port":2,"kind":"alwaysOn","dependencies":[],"resets":[{"name":"aonReset","port":0,"clock":"slow"}]}]}
