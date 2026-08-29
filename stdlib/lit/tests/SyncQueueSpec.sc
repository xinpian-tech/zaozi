// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.queue.default.SyncQueue" --

// RUN: rm -rf %t.dir && mkdir -p %t.dir

// Asynchronous reset with resettable RAM.
// RUN: cd %t.dir && %{test} config async.json --width 8 --depth 4 --almostEmptyLevel 1 --almostFullLevel 1 --stickyError false --enableDiagnostics false --asyncReset true --resetMem true
// RUN: cd %t.dir && %{test} design async.json
// RUN: cd %t.dir && firtool Ram_dataWidth8_depth4_asyncResettrue_resetMemtrue.mlirbc | FileCheck %s --check-prefix=ASYNC-RAM
// RUN: cd %t.dir && firtool SyncQueue_width8_depth4_almostEmptyLevel1_almostFullLevel1_stickyErrorfalse_enableDiagnosticsfalse_asyncResettrue_resetMemtrue.mlirbc | FileCheck %s --check-prefix=ASYNC

// Synchronous reset, non-power-of-two depth, sticky diagnostic mode.
// RUN: cd %t.dir && %{test} config sync.json --width 8 --depth 3 --almostEmptyLevel 1 --almostFullLevel 1 --stickyError true --enableDiagnostics true --asyncReset false --resetMem false
// RUN: cd %t.dir && %{test} design sync.json
// RUN: cd %t.dir && firtool Ram_dataWidth8_depth3_asyncResetfalse_resetMemfalse.mlirbc | FileCheck %s --check-prefix=SYNC-RAM
// RUN: cd %t.dir && firtool SyncQueue_width8_depth3_almostEmptyLevel1_almostFullLevel1_stickyErrortrue_enableDiagnosticstrue_asyncResetfalse_resetMemfalse.mlirbc | FileCheck %s --check-prefix=SYNC
// RUN: rm -rf %t.dir

// ASYNC-RAM-LABEL: module Ram_dataWidth8_depth4_asyncResettrue_resetMemtrue(
// ASYNC-RAM: input clock,
// ASYNC-RAM-NEXT: resetN,
// ASYNC-RAM-NEXT: chipSelectN,
// ASYNC-RAM-NEXT: writeN,
// ASYNC-RAM: wire [[RAM_RESET:[_A-Za-z0-9]+]] = ~resetN;
// ASYNC-RAM: always @(posedge clock or posedge [[RAM_RESET]]) begin
// ASYNC-RAM: if ([[RAM_RESET]]) begin

// SYNC-RAM-LABEL: module Ram_dataWidth8_depth3_asyncResetfalse_resetMemfalse(
// SYNC-RAM: input clock,
// SYNC-RAM-NEXT: resetN,
// SYNC-RAM-NOT: always @(posedge clock or
// SYNC-RAM: always @(posedge clock) begin
// SYNC-RAM-NOT: ~resetN

// ASYNC-LABEL: module SyncQueue_width8_depth4_almostEmptyLevel1_almostFullLevel1_stickyErrorfalse_enableDiagnosticsfalse_asyncResettrue_resetMemtrue_Verification();
// ASYNC: sync_queue_push_accept:
// ASYNC: clock)
// ASYNC-LABEL: module SyncQueue_width8_depth4_almostEmptyLevel1_almostFullLevel1_stickyErrorfalse_enableDiagnosticsfalse_asyncResettrue_resetMemtrue(
// ASYNC: input clock,
// ASYNC-NEXT: resetN,
// ASYNC-NEXT: pushRequestN,
// ASYNC-NEXT: popRequestN,
// ASYNC-NEXT: diagnosticN,
// ASYNC: input [7:0] dataIn,
// ASYNC: output empty,
// ASYNC-NEXT: almostEmpty,
// ASYNC-NEXT: halfFull,
// ASYNC-NEXT: almostFull,
// ASYNC-NEXT: full,
// ASYNC-NEXT: error,
// ASYNC: output [7:0] dataOut
// ASYNC: wire [[QUEUE_RESET:[_A-Za-z0-9]+]] = ~resetN;
// ASYNC: always @(posedge clock or posedge [[QUEUE_RESET]]) begin
// ASYNC: if ([[QUEUE_RESET]]) begin
// ASYNC: Incrementer_width2_radix4
// ASYNC: BrentKungAdder_width2_radix4
// ASYNC: Ram_dataWidth8_depth4_asyncResettrue_resetMemtrue ram (
// ASYNC-NOT: GTECH_

// SYNC-LABEL: module SyncQueue_width8_depth3_almostEmptyLevel1_almostFullLevel1_stickyErrortrue_enableDiagnosticstrue_asyncResetfalse_resetMemfalse_Verification();
// SYNC: sync_queue_push_accept:
// SYNC: clock)
// SYNC-LABEL: module SyncQueue_width8_depth3_almostEmptyLevel1_almostFullLevel1_stickyErrortrue_enableDiagnosticstrue_asyncResetfalse_resetMemfalse(
// SYNC: input clock,
// SYNC-NEXT: resetN,
// SYNC-NOT: always @(posedge clock or
// SYNC: always @(posedge clock) begin
// SYNC: if (~resetN) begin
// SYNC: Incrementer_width2_radix4
// SYNC: BrentKungAdder_width2_radix4
// SYNC: Ram_dataWidth8_depth3_asyncResetfalse_resetMemfalse ram (
// SYNC-NOT: GTECH_
