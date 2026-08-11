// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.ShiftReg" %s --
// DEFINE: %{bmc} = circt-bmc %t.dir/w4.bmc.hw.mlir --module=ShiftReg_width4_CheckContract_0 -b 4 --shared-libs=%Z3LIB --run

// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: not %{test} config %t.dir/w1.json --width 1 2>&1 | FileCheck %s -check-prefix=INVALID1
// RUN: %{test} config %t.dir/w4.json --width 4
// RUN: FileCheck %s -check-prefix=CONFIG4 --input-file=%t.dir/w4.json
// RUN: cd %t.dir && %{test} design %t.dir/w4.json
// RUN: circt-opt %t.dir/ShiftReg_width4.mlirbc | FileCheck %s -check-prefix=FIRRTL4
// RUN: firtool %t.dir/ShiftReg_width4.mlirbc > %t.dir/w4.sv
// RUN: FileCheck %s -check-prefix=VERILOG4 --input-file=%t.dir/w4.sv
// RUN: firtool %t.dir/ShiftReg_width4.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t.dir/w4.contract.hw.mlir --disable-output
// RUN: circt-opt %t.dir/w4.contract.hw.mlir --strip-om --symbol-dce -o %t.dir/w4.clean.hw.mlir
// RUN: FileCheck %s -check-prefix=LOWERED4 --input-file=%t.dir/w4.clean.hw.mlir
// RUN: circt-opt %t.dir/w4.clean.hw.mlir --pass-pipeline='builtin.module(verif-lower-tests,hw.module(lower-ltl-to-core,lower-seq-shiftreg,lower-seq-compreg-ce,canonicalize))' -o %t.dir/w4.bmc.hw.mlir
// RUN: %{bmc} | FileCheck %s -check-prefix=BMC4
// RUN: rm -rf %t.dir

// INVALID1: requirement failed: shift-register width must be greater than one, got 1
// CONFIG4: {"width":4}

// FIRRTL4: firrtl.contract
// FIRRTL4-COUNT-4: firrtl.int.ltl.clocked_past
// FIRRTL4: firrtl.int.ltl.clocked_delay
// FIRRTL4: firrtl.int.ltl.implication
// FIRRTL4: firrtl.int.verif.ensure
// FIRRTL4-SAME: shift_reg_transition

// VERILOG4-LABEL: module ShiftReg_width4(
// VERILOG4: input{{ +}}clock,
// VERILOG4-NEXT: serialInput,
// VERILOG4: input{{ +}}[3:0]{{ +}}parallelInput,
// VERILOG4: input{{ +}}shift,
// VERILOG4-NEXT: load,
// VERILOG4: output{{ +}}[3:0]{{ +}}parallelOutput
// VERILOG4: always @(posedge clock) begin
// VERILOG4: state <= parallelInput;
// VERILOG4: state <= {state[2:0], serialInput};
// VERILOG4: assign parallelOutput = state;

// LOWERED4-LABEL: verif.formal @ShiftReg_width4_CheckContract_0
// LOWERED4-COUNT-4: ltl.clocked_past
// LOWERED4: ltl.clocked_delay
// LOWERED4: ltl.implication
// LOWERED4: verif.assert
// LOWERED4-SAME: shift_reg_transition

// BMC4: Bound reached with no violations!
