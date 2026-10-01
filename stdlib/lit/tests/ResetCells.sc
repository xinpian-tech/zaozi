// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{pipeline} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.reset.ResetPipeline %s --
// DEFINE: %{counter} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.reset.ResetCounter %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{pipeline} config %t.dir/pipeline.json --stages 3
// RUN: cd %t.dir && %{pipeline} design %t.dir/pipeline.json
// RUN: firtool %t.dir/ResetPipeline_stages3.mlirbc --strip-debug-info | FileCheck %s --check-prefix=PIPELINE
// RUN: %{pipeline} config %t.dir/single.json --stages 1
// RUN: cd %t.dir && %{pipeline} design %t.dir/single.json
// RUN: firtool %t.dir/ResetPipeline_stages1.mlirbc --strip-debug-info | FileCheck %s --check-prefix=SINGLE
// RUN: %{counter} config %t.dir/counter.json --cycles 4
// RUN: cd %t.dir && %{counter} design %t.dir/counter.json
// RUN: firtool %t.dir/ResetCounter_cycles4.mlirbc --strip-debug-info | FileCheck %s --check-prefix=COUNTER
// RUN: rm -rf %t.dir

// PIPELINE: pipeline_asserts_on_clock:
// PIPELINE: assert property
// PIPELINE: pipeline_release_pending:
// PIPELINE: cover property
// PIPELINE: pipeline_released:
// PIPELINE: cover property
// PIPELINE: pipeline_test_bypass:
// PIPELINE: cover property
// PIPELINE-LABEL: module ResetPipeline_stages3(
// PIPELINE: always @(posedge clock) begin
// PIPELINE: stages <= 1'h0;
// PIPELINE-NEXT: stages_0 <= 1'h0;
// PIPELINE-NEXT: stages_1 <= 1'h0;
// PIPELINE: stages <= 1'h1;
// PIPELINE-NEXT: stages_0 <= stages;
// PIPELINE-NEXT: stages_1 <= stages_0;
// PIPELINE: assign output_0 = testEnable ? resetN : stages_1;

// SINGLE-LABEL: module ResetPipeline_stages1(
// SINGLE: reg stages;
// SINGLE-NOT: reg stages_
// SINGLE: always @(posedge clock) begin
// SINGLE: if (~resetN)
// SINGLE-NEXT: stages <= 1'h0;
// SINGLE-NEXT: else
// SINGLE-NEXT: stages <= 1'h1;
// SINGLE: assign output_0 = testEnable ? resetN : stages;

// COUNTER: counter_releases_at_terminal_count:
// COUNTER: assert property
// COUNTER: counter_release_pending:
// COUNTER: cover property
// COUNTER: counter_released:
// COUNTER: cover property
// COUNTER: counter_test_bypass:
// COUNTER: cover property
// COUNTER-LABEL: module ResetCounter_cycles4(
// COUNTER: reg {{ *}}[1:0] count;
// COUNTER: wire {{ *}}[[RESET:[a-zA-Z_0-9]+]] = ~resetN;
// COUNTER: wire {{ *}}[[LAST:[a-zA-Z_0-9]+]] = &count;
// COUNTER: always @(posedge clock or posedge [[RESET]]) begin
// COUNTER: count <= 2'h0;
// COUNTER: released <= 1'h0;
// COUNTER: count <= count + 2'h1;
// COUNTER: released <= {{.*}}[[LAST]] | released;
// COUNTER: assign output_0 = testEnable ? resetN : released;
