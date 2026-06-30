// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.stdcell.PrefixAdder" %S/PrefixAdder.sc --

// Use width 16 so this test does not race with PrefixAdder.sc over the same
// generated mlirbc filename when lit runs tests in parallel.
// RUN: %{test} config %t.json --width 16 --arch bka --algoParams radix=2
// RUN: %{test} design %t.json
// RUN: firtool PrefixAdder_archbka_width16.mlirbc > %t-impl.sv
// RUN: bash %S/../../../nix/scripts/jasper/formal.sh --top PrefixAdder_archbka_width16_jasper_top --design %t-impl.sv --assertion %S/PrefixAdderJasper.sv --out %t-bundle --force
// RUN: FileCheck %s -check-prefix=ASSERT --input-file=%t-bundle/assertions/PrefixAdderJasper.sv
// RUN: FileCheck %s -check-prefix=MANIFEST --input-file=%t-bundle/manifest.env
// RUN: FileCheck %s -check-prefix=TCL --input-file=%t-bundle/jasper_prove.tcl
// RUN: test -x %t-bundle/run_jasper.sh
// RUN: rm -rf %t.json %t-impl.sv PrefixAdder_archbka_width16.mlirbc %t-bundle

// ASSERT-LABEL: module PrefixAdder_archbka_width16_jasper_top(
// ASSERT: input logic [15:0] A,
// ASSERT: input logic [15:0] B,
// ASSERT: PrefixAdder_archbka_width16 dut
// ASSERT: expected_sum = {1'b0, A} + {1'b0, B} + {16'b0, CI};
// ASSERT: assert ({CO, SUM} == expected_sum);

// MANIFEST: TOP='PrefixAdder_archbka_width16_jasper_top'
// MANIFEST: DESIGN_FILES='design/PrefixAdderJasper.sc.tmp-impl.sv'
// MANIFEST: ASSERTION_FILES='assertions/PrefixAdderJasper.sv'

// TCL: analyze -sv $f
// TCL: elaborate -top $top
// TCL: prove -all
