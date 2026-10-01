// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.clock.ClockGate %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/positive.json --positive true --clockDuringReset false
// RUN: cd %t.dir && %{test} design %t.dir/positive.json
// RUN: firtool %t.dir/ClockGate_positivetrue_clockDuringResetfalse.mlirbc --strip-debug-info | FileCheck %s --check-prefixes=COVER,DIRECT,POSITIVE
// RUN: %{test} config %t.dir/negative.json --positive false --clockDuringReset false
// RUN: cd %t.dir && %{test} design %t.dir/negative.json
// RUN: firtool %t.dir/ClockGate_positivefalse_clockDuringResetfalse.mlirbc --strip-debug-info | FileCheck %s --check-prefixes=COVER,DIRECT,NEGATIVE
// RUN: %{test} config %t.dir/reset-positive.json --positive true --clockDuringReset true
// RUN: cd %t.dir && %{test} design %t.dir/reset-positive.json
// RUN: firtool %t.dir/ClockGate_positivetrue_clockDuringResettrue.mlirbc --strip-debug-info | FileCheck %s --check-prefixes=COVER,RESET,POSITIVE
// RUN: %{test} config %t.dir/reset-negative.json --positive false --clockDuringReset true
// RUN: cd %t.dir && %{test} design %t.dir/reset-negative.json
// RUN: firtool %t.dir/ClockGate_positivefalse_clockDuringResettrue.mlirbc --strip-debug-info | FileCheck %s --check-prefixes=COVER,RESET,NEGATIVE
// RUN: rm -rf %t.dir

// COVER: gate_requested:
// COVER: cover property
// COVER: gate_disable_requested:
// COVER: cover property
// COVER: gate_test_enable:
// COVER: cover property
// RESET: gate_reset_enable:
// RESET: cover property

// COVER-LABEL: module ClockGate_{{positive(true|false)_clockDuringReset(true|false)}}(
// COVER-NOT: ZaoziClockMux
// RESET: wire [[RESET:[a-zA-Z_0-9]+]] = ~_receiver_synchronizedReset;
// POSITIVE: ZaoziClockGatePositive gate (
// NEGATIVE: ZaoziClockGateNegative gate (
// COVER-NEXT: .a{{ +}}(clock),
// DIRECT-NEXT: .enable{{ +}}(enable | testEnable),
// RESET-NEXT: .enable{{ +}}(enable | testEnable | [[RESET]]),
// COVER-NEXT: .outClock (output_0)
// RESET: SynchronizedReset_stages2_activeLow receiver (
// RESET-NEXT: .clock{{ +}}(clock),
// RESET-NEXT: .reset{{ +}}(resetN),
// COVER-NOT: ZaoziClockMux
// COVER: endmodule
