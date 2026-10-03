// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.clock.ClockTree %s --
// DEFINE: %{cells} = --cells '{"name":"INV","inputs":["A"],"output":"Y","function":[{"A":0,"Y":1},{"A":1,"Y":0}]}' --cells '{"name":"BUF","inputs":["A"],"output":"Y","function":[{"Y":"A"}]}' --cells '{"name":"ICG","sequential":{"type":"GatePositive","clock":"CK","enable":"E","test":"SE","output":"Q"}}'
// RUN: rm -rf %t.dir && mkdir -p %t.dir/bound %t.dir/unbound
// RUN: %{test} config %t.dir/bound/config.json --inputs a --targets '{"name":"out","links":[{"source":"a","path":{"gate":{"positive":false,"clockDuringReset":false},"invert":true}}],"path":{"inverterGuide":{"instance":"plain_guide"},"invert":true}}' %{cells}
// RUN: cd %t.dir/bound && %{test} design %t.dir/bound/config.json
// RUN: firld --base-circuit=$(basename %t.dir/bound/ClockTree_????????.mlirbc .mlirbc) %t.dir/bound/*.mlirbc -o %t.dir/bound/tree.mlir
// RUN: firtool %t.dir/bound/tree.mlir --strip-debug-info --split-verilog -o %t.dir/bound/rtl
// RUN: cd %t.dir/bound/rtl && FileCheck %s --check-prefix=BUF --input-file=$(grep -l "BUF libraryCell" *.sv)
// RUN: cd %t.dir/bound/rtl && FileCheck %s --check-prefix=ICG --input-file=$(grep -l "ICG libraryCell" *.sv)
// RUN: cd %t.dir/bound/rtl && FileCheck %s --check-prefix=INV --input-file=$(grep -l "INV libraryCell" ClockCellNetwork_*.sv)
// RUN: cd %t.dir/bound/rtl && FileCheck %s --check-prefix=NEGATIVE --input-file=$(grep -l " inverted (" ClockCellNetwork_*.sv) -DINV=$(basename $(grep -l "INV libraryCell" ClockCellNetwork_*.sv) .sv) -DICG=$(basename $(grep -l "ICG libraryCell" *.sv) .sv)
// RUN: cd %t.dir/bound/rtl && FileCheck %s --check-prefix=GATE --input-file=ClockGate_positivefalse_clockDuringResetfalse.sv
// RUN: cd %t.dir/bound/rtl && FileCheck %s --check-prefix=MODEL --input-file=$(grep -l "ZaoziClockGateNegative model" ClockCellModel_*.sv)
// RUN: cd %t.dir/bound/rtl && FileCheck %s --check-prefix=TREE --input-file=$(ls ClockTree_????????.sv)
// RUN: not %{test} config %t.dir/unbound/config.json --inputs a --targets '{"name":"out","links":[{"source":"a","path":{"gate":{"positive":true,"clockDuringReset":false}}}]}' --cells '{"name":"INV","inputs":["A"],"output":"Y","function":[{"Y":"!A"}]}' 2>&1 | FileCheck %s --check-prefix=UNBOUND
// RUN: rm -rf %t.dir

// BUF:      BUF libraryCell (
// BUF-NEXT:   .A (a),
// BUF-NEXT:   .Y (outClock)

// ICG:      ICG libraryCell (
// ICG-NEXT:   .CK (a),
// ICG-NEXT:   .E{{ +}}(enable),
// ICG-NEXT:   .SE (1'h0),
// ICG-NEXT:   .Q{{ +}}(outClock)

// INV:      INV libraryCell (
// INV-NEXT:   .A (a),
// INV-NEXT:   .Y (outClock)

// The negative gate is the positive gate between two inverters.
// NEGATIVE: [[INV]] inverted (
// NEGATIVE: [[ICG]] gated (
// NEGATIVE: [[INV]] restored (

// GATE: `ifdef targets$ClockCellIO$Model
// GATE:   ClockCellModel_{{[a-f0-9]+}} gate_Model (
// GATE: `ifdef targets$ClockCellIO$Library
// GATE:   ClockCellNetwork_{{[a-f0-9]+}} gate_Library (

// MODEL: ZaoziClockGateNegative model (

// TREE: `ifdef targets$ClockCellIO$Model
// TREE:   ClockCellModel_{{[a-f0-9]+}} plain_guide_Model (
// TREE: `ifdef targets$ClockCellIO$Library
// TREE:   ClockCellNetwork_{{[a-f0-9]+}} target_0_link_0_inverter_Library (
// TREE:   ClockCellNetwork_{{[a-f0-9]+}} target_0_inverter_Library (
// TREE:   ClockCellNetwork_{{[a-f0-9]+}} plain_guide_Library (

// UNBOUND: clock role GatePositive is used but no declared cell implements it
