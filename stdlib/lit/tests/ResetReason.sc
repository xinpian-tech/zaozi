// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.reset.ResetReason %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/reason.json --sources 2
// RUN: cd %t.dir && %{test} design %t.dir/reason.json
// RUN: firtool %t.dir/ResetReason_sources2.mlirbc --strip-debug-info | FileCheck %s --check-prefix=RTL
// RUN: rm -rf %t.dir

// RTL: clear_invalidates_reasons:
// RTL: assert property
// RTL: reset_reason_captured:
// RTL: cover property
// RTL: event_during_clear:
// RTL: cover property
// RTL: clear_retriggered:
// RTL: cover property
// RTL-LABEL: module ResetReason_sources2(
// RTL: clearWindow <= 2'h3;
// RTL: clearWindow <= {1'h0, clearWindow[1]};
// RTL: always @(posedge clock or posedge events_0) begin
// RTL-NEXT: if (events_0)
// RTL-NEXT: flag <= 1'h1;
// RTL: always @(posedge clock or posedge events_1) begin
// RTL-NEXT: if (events_1)
// RTL-NEXT: flag_0 <= 1'h1;
// RTL: assign reasons = valid_0 ? {{[a-zA-Z_0-9]+}} : 2'h0;
// RTL: assign valid = valid_0;
