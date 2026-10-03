// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.clock.ClockDivider %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir/auto %t.dir/handshake
// RUN: %{test} config %t.dir/auto/divider.json --width 4 --initial 1 --clockDuringReset false --automatic true
// RUN: cd %t.dir/auto && %{test} design %t.dir/auto/divider.json
// RUN: firld --base-circuit=$(basename %t.dir/auto/ClockDivider_*.mlirbc .mlirbc) %t.dir/auto/*.mlirbc -o %t.dir/auto/divider.mlir
// RUN: firtool %t.dir/auto/divider.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv --lowering-options=disallowLocalVariables -o %t.dir/auto/divider.sv
// RUN: FileCheck %s --check-prefix=CORE < %t.dir/auto/divider.sv
// RUN: FileCheck %s --check-prefix=AUTO < %t.dir/auto/divider.sv
// RUN: %{test} config %t.dir/handshake/divider.json --width 4 --initial 1 --clockDuringReset false --automatic false
// RUN: cd %t.dir/handshake && %{test} design %t.dir/handshake/divider.json
// RUN: firld --base-circuit=$(basename %t.dir/handshake/ClockDivider_*.mlirbc .mlirbc) %t.dir/handshake/*.mlirbc -o %t.dir/handshake/divider.mlir
// RUN: firtool %t.dir/handshake/divider.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv --lowering-options=disallowLocalVariables -o %t.dir/handshake/divider.sv
// RUN: FileCheck %s --check-prefix=CORE < %t.dir/handshake/divider.sv
// RUN: FileCheck %s --check-prefix=HANDSHAKE < %t.dir/handshake/divider.sv
// RUN: rm -rf %t.dir

// CORE: divider_count_in_period:
// CORE: assert property
// CORE: accept_bypass:
// CORE: accept_odd_divisor:
// CORE: accept_even_divisor:
// CORE: divisor_pending:
// CORE: divider_disabled:
// CORE-LABEL: module ClockDivider_{{[a-f0-9]+}}(
// CORE: wire [4:0] oddHalf = {{[a-zA-Z_0-9]+}} + 5'h1;
// CORE: always @(negedge clock or posedge
// CORE: count_0 == oddHalf[4:1]

// AUTO: divider_request_held:
// AUTO: assert property
// AUTO: divider_update_while_busy:
// AUTO: cover property
// AUTO: divider_update_accepted:
// AUTO: cover property
// AUTO-LABEL: module ClockDivider_{{[a-f0-9]+}}(
// AUTO-NOT: input{{.*}}valid
// AUTO-NOT: output{{.*}}ready
// AUTO: );
// AUTO: if (pending)
// AUTO-NEXT: pending <= ~[[READY:[a-zA-Z_0-9]+]];
// AUTO-NEXT: else begin
// AUTO-NEXT: request <= latest_layerCapture;

// HANDSHAKE-LABEL: module ClockDivider_{{[a-f0-9]+}}(
// HANDSHAKE: input{{ *}}valid,
// HANDSHAKE: output{{ *}}ready,
// HANDSHAKE-NOT: reg{{.*}}pending
// HANDSHAKE-NOT: reg{{.*}}sampled
