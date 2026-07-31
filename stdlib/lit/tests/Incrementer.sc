// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.Incrementer" %s --
// DEFINE: %{bmc} = circt-bmc %t.dir/w8.contract.hw.mlir --module=Incrementer_width8_radix4_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run

// width 8
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/w8.json --width 8 --radix 4
// RUN: FileCheck %s -check-prefix=CONFIG8 --input-file=%t.dir/w8.json
// RUN: cd %t.dir && %{test} design %t.dir/w8.json
// RUN: circt-opt %t.dir/Incrementer_width8_radix4.mlirbc | FileCheck %s -check-prefix=CONTRACT8
// RUN: firtool %t.dir/Incrementer_width8_radix4.mlirbc | FileCheck %s -check-prefix=VERILOG8
// RUN: firtool %t.dir/Incrementer_width8_radix4.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t.dir/w8.contract.hw.mlir --disable-output
// RUN: FileCheck %s -check-prefix=LOWERED8 --input-file=%t.dir/w8.contract.hw.mlir
// RUN: %{bmc} | FileCheck %s -check-prefix=BMC8
// RUN: rm -rf %t.dir

// CONFIG8: {"width":8}

// CONTRACT8: firrtl.contract
// CONTRACT8: firrtl.int.verif.ensure

// VERILOG8-LABEL: module Incrementer_width8_radix4
// VERILOG8: input{{ +}}[7:0]{{ +}}A,
// VERILOG8: output{{ +}}[7:0]{{ +}}SUM

// LOWERED8-LABEL: hw.module @Incrementer_width8_radix4
// LOWERED8: verif.assume
// LOWERED8-SAME: incrementer_matches_add
// LOWERED8-LABEL: verif.formal @Incrementer_width8_radix4_CheckContract_0
// LOWERED8: verif.assert
// LOWERED8-SAME: incrementer_matches_add

// BMC8: Bound reached with no violations!
