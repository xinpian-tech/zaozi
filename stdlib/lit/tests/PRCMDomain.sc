// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.prcm.PRCMDomain %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/domain.json --resetStages 2
// RUN: cd %t.dir && %{test} design %t.dir/domain.json
// RUN: firld --base-circuit=PRCMDomain_resetStages2 %t.dir/*.mlirbc -o %t.dir/domain.mlir
// RUN: firtool %t.dir/domain.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv | FileCheck %s --check-prefix=RTL
// RUN: rm -rf %t.dir

// RTL: power_loss_enters_fault:
// RTL: reset_release_is_held_until_receiver:
// RTL: domain_off:
// RTL: domain_reset:
// RTL: domain_run:
// RTL: power_request_reversed:
// RTL: reset_release_reversed:
// RTL: quiesce_return_reversed:
// RTL: domain_power_lost:
// RTL: domain_service_lost:
// RTL-LABEL: module PRCMDomain_resetStages2(
// RTL: resetFeedback <= ~[[RESET:[a-zA-Z_0-9]+]];
// RTL: resetFeedback_0 <= resetFeedback;
// RTL: ClockGate_positivetrue_clockDuringResetfalse gate (
// RTL: .testEnable (1'h0)
// RTL: SynchronizedReset_stages2_activeLow receiver (
// RTL: .clock{{ +}}(_gate_output)
// RTL: .synchronizedReset ([[RESET]])
// RTL: assign domainClock = _gate_output;
// RTL: assign domainResetN = [[RESET]];
