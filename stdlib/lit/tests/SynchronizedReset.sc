// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.default.SynchronizedReset %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/config.json --stages 2 --polarity active-high
// RUN: cd %t.dir && %{test} design %t.dir/config.json
// RUN: firld %t.dir/SynchronizedReset_stages2_activeHigh.mlirbc --base-circuit SynchronizedReset_stages2_activeHigh --no-mangle | firtool --format=mlir | FileCheck %s
// RUN: rm -rf %t.dir

// CHECK-LABEL: module SynchronizedReset_stages2_activeHigh(
// CHECK: input  clock,
// CHECK: reset,
// CHECK: output synchronizedReset
// CHECK: always @(posedge clock or posedge reset) begin
// CHECK: if (reset) begin
// CHECK: synchronizationStages <= 1'h1;
// CHECK: synchronizationStages_0 <= 1'h1;
// CHECK: end
// CHECK: else begin
// CHECK: synchronizationStages <= 1'h0;
// CHECK: synchronizationStages_0 <= synchronizationStages;
// CHECK: end
// CHECK: assign synchronizedReset = synchronizationStages_0;
