// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.queue.default.AsyncQueue" --

// RUN: rm -rf %t.dir && mkdir -p %t.dir

// Power-of-two queue with asynchronous reset.
// RUN: cd %t.dir && %{test} config async.json --width 8 --depth 4 --pushAlmostEmptyLevel 1 --pushAlmostFullLevel 1 --popAlmostEmptyLevel 1 --popAlmostFullLevel 1 --stickyError false --pushSync 2 --popSync 3 --asyncReset true --resetMem true
// RUN: cd %t.dir && %{test} design async.json
// RUN: cd %t.dir && firtool Ram_dataWidth8_depth4_asyncResettrue_resetMemtrue.mlirbc | FileCheck %s --check-prefix=ASYNC-RAM
// RUN: cd %t.dir && firtool AsyncQueue_width8_depth4_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync3_asyncResettrue_resetMemtrue.mlirbc | FileCheck %s --check-prefix=ASYNC

// Non-power-of-two queue exercises centered-pointer correction and the effective RAM depth.
// RUN: cd %t.dir && %{test} config nonpow.json --width 8 --depth 5 --pushAlmostEmptyLevel 2 --pushAlmostFullLevel 1 --popAlmostEmptyLevel 1 --popAlmostFullLevel 2 --stickyError true --pushSync 1 --popSync 2 --asyncReset false --resetMem false
// RUN: cd %t.dir && %{test} design nonpow.json
// RUN: cd %t.dir && firtool Ram_dataWidth8_depth6_asyncResetfalse_resetMemfalse.mlirbc | FileCheck %s --check-prefix=NONPOW-RAM
// RUN: cd %t.dir && firtool AsyncQueue_width8_depth5_pushAlmostEmptyLevel2_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel2_stickyErrortrue_pushSync1_popSync2_asyncResetfalse_resetMemfalse.mlirbc | FileCheck %s --check-prefix=NONPOW

// Residual greater than one uses ordinary per-bit muxes for wrapped-count correction.
// RUN: cd %t.dir && %{test} config mux.json --width 8 --depth 9 --pushAlmostEmptyLevel 1 --pushAlmostFullLevel 1 --popAlmostEmptyLevel 1 --popAlmostFullLevel 1 --stickyError false --pushSync 2 --popSync 2 --asyncReset false --resetMem false
// RUN: cd %t.dir && %{test} design mux.json
// RUN: cd %t.dir && firtool AsyncQueue_width8_depth9_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync2_asyncResetfalse_resetMemfalse.mlirbc | FileCheck %s --check-prefix=MUX
// RUN: rm -rf %t.dir

// ASYNC-RAM-LABEL: module Ram_dataWidth8_depth4_asyncResettrue_resetMemtrue(
// ASYNC-RAM: wire [[RAM_RESET:[_A-Za-z0-9]+]] = ~resetN;
// ASYNC-RAM: always @(posedge clock or posedge [[RAM_RESET]]) begin
// ASYNC-RAM: if ([[RAM_RESET]]) begin

// NONPOW-RAM-LABEL: module Ram_dataWidth8_depth6_asyncResetfalse_resetMemfalse(
// NONPOW-RAM-NOT: always @(posedge clock or
// NONPOW-RAM: always @(posedge clock) begin
// NONPOW-RAM-NOT: ~resetN

// ASYNC-LABEL: module AsyncQueue_width8_depth4_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync3_asyncResettrue_resetMemtrue_Verification();
// ASYNC: async_queue_push_accept:
// ASYNC: push_clock)
// ASYNC: async_queue_overflow_request:
// ASYNC: async_queue_pop_accept:
// ASYNC: pop_clock)
// ASYNC: async_queue_underflow_request:
// ASYNC-LABEL: module AsyncQueue_width8_depth4_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync3_asyncResettrue_resetMemtrue(
// ASYNC: input resetN,
// ASYNC-NEXT: push_clock,
// ASYNC-NEXT: push_requestN,
// ASYNC: output push_empty,
// ASYNC-NEXT: push_almostEmpty,
// ASYNC-NEXT: push_halfFull,
// ASYNC-NEXT: push_almostFull,
// ASYNC-NEXT: push_full,
// ASYNC-NEXT: push_error,
// ASYNC: input pop_clock,
// ASYNC-NEXT: pop_requestN,
// ASYNC: output pop_empty,
// ASYNC-NEXT: pop_almostEmpty,
// ASYNC-NEXT: pop_halfFull,
// ASYNC-NEXT: pop_almostFull,
// ASYNC-NEXT: pop_full,
// ASYNC-NEXT: pop_error,
// ASYNC: input [7:0] dataIn,
// ASYNC: output [7:0] dataOut
// ASYNC: wire [[QUEUE_RESET:[_A-Za-z0-9]+]] = ~resetN;
// ASYNC: always @(posedge push_clock or posedge [[QUEUE_RESET]]) begin
// ASYNC: if ([[QUEUE_RESET]]) begin
// ASYNC: always @(posedge pop_clock or posedge [[QUEUE_RESET]]) begin
// ASYNC: if ([[QUEUE_RESET]]) begin
// ASYNC: Incrementer_width3_radix4
// ASYNC: BrentKungAdder_width3_radix4
// ASYNC: Ram_dataWidth8_depth4_asyncResettrue_resetMemtrue ram (
// ASYNC-NOT: SynchronizedReset
// ASYNC-NOT: GTECH_

// NONPOW-LABEL: module AsyncQueue_width8_depth5_pushAlmostEmptyLevel2_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel2_stickyErrortrue_pushSync1_popSync2_asyncResetfalse_resetMemfalse_Verification();
// NONPOW: async_queue_push_sticky_error_retained:
// NONPOW: push_clock)
// NONPOW: async_queue_pop_sticky_error_retained:
// NONPOW: pop_clock)
// NONPOW-LABEL: module AsyncQueue_width8_depth5_pushAlmostEmptyLevel2_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel2_stickyErrortrue_pushSync1_popSync2_asyncResetfalse_resetMemfalse(
// NONPOW-NOT: always @(posedge push_clock or
// NONPOW-NOT: always @(posedge pop_clock or
// NONPOW: always @(posedge push_clock) begin
// NONPOW: if ({{[_A-Za-z0-9]+}}) begin
// NONPOW: always @(posedge pop_clock) begin
// NONPOW: if ({{[_A-Za-z0-9]+}}) begin
// NONPOW: Incrementer_width3_radix4
// NONPOW: BrentKungAdder_width3_radix4
// NONPOW: BrentKungAdder_width2_radix4
// NONPOW: Ram_dataWidth8_depth6_asyncResetfalse_resetMemfalse ram (
// NONPOW-NOT: SynchronizedReset
// NONPOW-NOT: GTECH_

// MUX-LABEL: module AsyncQueue_width8_depth9_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync2_asyncResetfalse_resetMemfalse(
// MUX: Incrementer_width4_radix4
// MUX: BrentKungAdder_width4_radix4
// MUX: BrentKungAdder_width3_radix4
// MUX: Ram_dataWidth8_depth10_asyncResetfalse_resetMemfalse ram (
// MUX-NOT: SynchronizedReset
// MUX-NOT: GTECH_
