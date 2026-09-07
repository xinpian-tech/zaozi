// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{core} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.queue.default.AsyncQueue" --
// DEFINE: %{single} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.queue.default.SingleEntryAsyncQueue" --

// RUN: rm -rf %t.dir %t.depth1.dir && mkdir -p %t.dir %t.depth1.dir

// Minimum multi-entry power-of-two queue.
// RUN: cd %t.dir && %{core} config depth2.json --width 8 --depth 2 --pushAlmostEmptyLevel 1 --pushAlmostFullLevel 1 --popAlmostEmptyLevel 1 --popAlmostFullLevel 1 --stickyError false --pushSync 2 --popSync 2 --asyncReset true --resetMem true
// RUN: cd %t.dir && %{core} design depth2.json
// RUN: cd %t.dir && firtool Ram_dataWidth8_depth2_asyncResettrue_resetMemtrue.mlirbc | FileCheck %s --check-prefix=DEPTH2-RAM
// RUN: cd %t.dir && firtool AsyncQueue_width8_depth2_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync2_asyncResettrue_resetMemtrue.mlirbc | FileCheck %s --check-prefix=DEPTH2

// Depth three uses a four-state pointer ring and a four-entry physical RAM.
// RUN: cd %t.dir && %{core} config depth3.json --width 8 --depth 3 --pushAlmostEmptyLevel 1 --pushAlmostFullLevel 1 --popAlmostEmptyLevel 1 --popAlmostFullLevel 1 --stickyError false --pushSync 1 --popSync 1 --asyncReset false --resetMem false
// RUN: cd %t.dir && %{core} design depth3.json
// RUN: cd %t.dir && firtool Ram_dataWidth8_depth4_asyncResetfalse_resetMemfalse.mlirbc | FileCheck %s --check-prefix=DEPTH3-RAM
// RUN: cd %t.dir && firtool AsyncQueue_width8_depth3_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync1_popSync1_asyncResetfalse_resetMemfalse.mlirbc | FileCheck %s --check-prefix=DEPTH3

// Power-of-two queue with asynchronous reset.
// RUN: cd %t.dir && %{core} config async.json --width 8 --depth 4 --pushAlmostEmptyLevel 1 --pushAlmostFullLevel 1 --popAlmostEmptyLevel 1 --popAlmostFullLevel 1 --stickyError false --pushSync 2 --popSync 3 --asyncReset true --resetMem true
// RUN: cd %t.dir && %{core} design async.json
// RUN: cd %t.dir && firtool Ram_dataWidth8_depth4_asyncResettrue_resetMemtrue.mlirbc | FileCheck %s --check-prefix=ASYNC-RAM
// RUN: cd %t.dir && firtool AsyncQueue_width8_depth4_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync3_asyncResettrue_resetMemtrue.mlirbc | FileCheck %s --check-prefix=ASYNC

// Non-power-of-two queue exercises centered-pointer correction and the effective RAM depth.
// RUN: cd %t.dir && %{core} config nonpow.json --width 8 --depth 5 --pushAlmostEmptyLevel 2 --pushAlmostFullLevel 1 --popAlmostEmptyLevel 1 --popAlmostFullLevel 2 --stickyError true --pushSync 1 --popSync 2 --asyncReset false --resetMem false
// RUN: cd %t.dir && %{core} design nonpow.json
// RUN: cd %t.dir && firtool Ram_dataWidth8_depth6_asyncResetfalse_resetMemfalse.mlirbc | FileCheck %s --check-prefix=NONPOW-RAM
// RUN: cd %t.dir && firtool AsyncQueue_width8_depth5_pushAlmostEmptyLevel2_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel2_stickyErrortrue_pushSync1_popSync2_asyncResetfalse_resetMemfalse.mlirbc | FileCheck %s --check-prefix=NONPOW

// Residual greater than one uses ordinary per-bit muxes for wrapped-count correction.
// RUN: cd %t.dir && %{core} config mux.json --width 8 --depth 9 --pushAlmostEmptyLevel 1 --pushAlmostFullLevel 1 --popAlmostEmptyLevel 1 --popAlmostFullLevel 1 --stickyError false --pushSync 2 --popSync 2 --asyncReset false --resetMem false
// RUN: cd %t.dir && %{core} design mux.json
// RUN: cd %t.dir && firtool AsyncQueue_width8_depth9_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync2_asyncResetfalse_resetMemfalse.mlirbc | FileCheck %s --check-prefix=MUX

// Depth one uses the single-entry async queue.
// RUN: cd %t.depth1.dir && %{single} config depth1.json --width 8 --depth 1 --pushAlmostEmptyLevel 1 --pushAlmostFullLevel 1 --popAlmostEmptyLevel 1 --popAlmostFullLevel 1 --stickyError false --pushSync 2 --popSync 2 --asyncReset true --resetMem true
// RUN: cd %t.depth1.dir && %{single} design depth1.json
// RUN: cd %t.depth1.dir && firtool SingleEntryAsyncQueue_width8_depth1_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync2_asyncResettrue_resetMemtrue.mlirbc | FileCheck %s --check-prefix=DEPTH1
// RUN: rm -rf %t.dir %t.depth1.dir

// DEPTH2-RAM-LABEL: module Ram_dataWidth8_depth2_asyncResettrue_resetMemtrue(

// DEPTH3-RAM-LABEL: module Ram_dataWidth8_depth4_asyncResetfalse_resetMemfalse(

// DEPTH2-LABEL: module AsyncQueue_width8_depth2_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync2_asyncResettrue_resetMemtrue(
// DEPTH2: Incrementer_width2_radix4
// DEPTH2: BrentKungAdder_width2_radix4
// DEPTH2: Ram_dataWidth8_depth2_asyncResettrue_resetMemtrue ram (

// DEPTH3-LABEL: module AsyncQueue_width8_depth3_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync1_popSync1_asyncResetfalse_resetMemfalse(
// DEPTH3: Incrementer_width2_radix4
// DEPTH3: BrentKungAdder_width2_radix4
// DEPTH3: Ram_dataWidth8_depth4_asyncResetfalse_resetMemfalse ram (

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

// DEPTH1-LABEL: module SingleEntryAsyncQueue_width8_depth1_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync2_asyncResettrue_resetMemtrue_Verification();
// DEPTH1: single_entry_async_queue_push_accept:
// DEPTH1: single_entry_async_queue_pop_accept:
// DEPTH1-LABEL: module SingleEntryAsyncQueue_width8_depth1_pushAlmostEmptyLevel1_pushAlmostFullLevel1_popAlmostEmptyLevel1_popAlmostFullLevel1_stickyErrorfalse_pushSync2_popSync2_asyncResettrue_resetMemtrue(
// DEPTH1: input resetN,
// DEPTH1-NEXT: push_clock,
// DEPTH1-NEXT: push_requestN,
// DEPTH1: output push_empty,
// DEPTH1: input pop_clock,
// DEPTH1-NEXT: pop_requestN,
// DEPTH1: output pop_empty,
// DEPTH1: input [7:0] dataIn,
// DEPTH1: output [7:0] dataOut
