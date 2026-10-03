// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.power.PowerControl %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir/switched %t.dir/unswitched
// RUN: %{test} config %t.dir/switched/config.json --hasSwitch true --waitDependencyCycles 3 --settleOnCycles 5 --settleOffCycles 4
// RUN: cd %t.dir/switched && %{test} design %t.dir/switched/config.json
// RUN: firld --base-circuit=PowerControl_f64596b1 %t.dir/switched/*.mlirbc -o %t.dir/switched/control.mlir
// RUN: firtool %t.dir/switched/control.mlir --strip-debug-info --lowering-options=disallowLocalVariables | FileCheck %s --check-prefix=SWITCHED
// RUN: %{test} config %t.dir/unswitched/config.json --hasSwitch false --waitDependencyCycles 1 --settleOnCycles 1 --settleOffCycles 1
// RUN: cd %t.dir/unswitched && %{test} design %t.dir/unswitched/config.json
// RUN: firld --base-circuit=PowerControl_16e6eff9 %t.dir/unswitched/*.mlirbc -o %t.dir/unswitched/control.mlir
// RUN: firtool %t.dir/unswitched/control.mlir --strip-debug-info --lowering-options=disallowLocalVariables | FileCheck %s --check-prefix=UNSWITCHED
// RUN: rm -rf %t.dir

// SWITCHED: clock_precedes_reset_release:
// SWITCHED: reset_precedes_clock_stop:
// SWITCHED: power_resetassert:
// SWITCHED: soft_dependency_timeout:
// SWITCHED: hard_dependency_timeout:
// SWITCHED: power_on_timeout:
// SWITCHED: power_off_timeout:
// SWITCHED: fault_retry:
// SWITCHED: on_ignores_power_loss:
// SWITCHED-LABEL: module PowerControl_f64596b1(
// SWITCHED: wire {{ *}}_GEN_3 = hardReady & (softReady | waitExpired_layerCapture);
// SWITCHED: : _GEN_17
// SWITCHED-NEXT: ? 3'h7
// SWITCHED: always @(posedge clock or posedge _GEN) begin
// SWITCHED: if (phase != 3'h4 & next == 3'h4)
// SWITCHED-NEXT: offCount <= 3'h3;
// SWITCHED: ~(at_layerCapture_6 & faultClear)
// SWITCHED-NEXT: & (softMiss_layerCapture | next == 3'h5 | fault_0);

// UNSWITCHED-LABEL: module PowerControl_16e6eff9(
// UNSWITCHED: assign powerSwitch = 1'h0;
