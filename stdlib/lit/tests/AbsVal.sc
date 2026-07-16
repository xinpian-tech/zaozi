// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.AbsVal" %s --
// DEFINE: %{bmc} = circt-bmc %t-w8.clean.hw.mlir --module=AbsVal_width8_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run

// width 8
// RUN: %{test} config %t-w8.json --width 8
// RUN: FileCheck %s -check-prefix=CONFIG8 --input-file=%t-w8.json
// RUN: mkdir -p %t-w8.modules
// RUN: cd %t-w8.modules && %{test} design %t-w8.json
// RUN: firld --base-circuit=AbsVal_width8 %t-w8.modules/AbsVal_width8.mlirbc %t-w8.modules/Incrementer_width8_radix4.mlirbc -o %t-w8.linked.mlir
// RUN: circt-opt %t-w8.linked.mlir | FileCheck %s -check-prefix=CONTRACT8
// RUN: firtool %t-w8.linked.mlir > %t-w8.sv
// RUN: FileCheck %s -check-prefix=VERILOG8 --input-file=%t-w8.sv
// RUN: firtool %t-w8.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w8.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w8.contract.hw.mlir --strip-om --symbol-dce -o %t-w8.clean.hw.mlir
// RUN: FileCheck %s -check-prefix=LOWERED8 --input-file=%t-w8.clean.hw.mlir
// RUN: %{bmc} | FileCheck %s -check-prefix=BMC8
// RUN: rm %t-w8.json %t-w8.linked.mlir %t-w8.sv %t-w8.contract.hw.mlir %t-w8.clean.hw.mlir -f
// RUN: rm -rf %t-w8.modules

// CONFIG8: {"width":8}

// CONTRACT8: firrtl.contract
// CONTRACT8: firrtl.int.verif.ensure
// CONTRACT8: absval_matches_abs
// CONTRACT8: incrementer_matches_add

// VERILOG8-LABEL: module AbsVal_width8
// VERILOG8: input{{ +}}[7:0]{{ +}}A,
// VERILOG8: output{{ +}}[7:0]{{ +}}ABSVAL
// VERILOG8: Incrementer_width8_radix4 neg
// VERILOG8-LABEL: module Incrementer_width8_radix4

// LOWERED8-LABEL: verif.formal @AbsVal_width8_CheckContract_0
// LOWERED8: hw.instance
// LOWERED8-SAME: @Incrementer_width8_radix4
// LOWERED8: verif.assert
// LOWERED8-SAME: absval_matches_abs
// LOWERED8-LABEL: hw.module @Incrementer_width8_radix4
// LOWERED8: verif.assume
// LOWERED8-SAME: incrementer_matches_add

// BMC8: Bound reached with no violations!
