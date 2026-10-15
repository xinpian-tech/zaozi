// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.adder.default.RippleAdder" %s --
// DEFINE: %{bmc} = circt-bmc -b 1 --shared-libs=%Z3LIB --run

// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/w1.json --width 1
// RUN: cd %t.dir && %{test} design %t.dir/w1.json
// RUN: firtool %t.dir/RippleAdder_width1.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t.dir/w1.hw.mlir --disable-output
// RUN: %{bmc} %t.dir/w1.hw.mlir --module=RippleAdder_width1_CheckContract_0 | FileCheck %s --check-prefix=BMC

// RUN: %{test} config %t.dir/w5.json --width 5
// RUN: FileCheck %s --check-prefix=CONFIG --input-file=%t.dir/w5.json
// RUN: cd %t.dir && %{test} design %t.dir/w5.json
// RUN: circt-opt %t.dir/RippleAdder_width5.mlirbc | FileCheck %s --check-prefix=STRUCTURE
// RUN: firtool %t.dir/RippleAdder_width5.mlirbc | FileCheck %s --check-prefix=VERILOG
// RUN: firtool %t.dir/RippleAdder_width5.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t.dir/w5.hw.mlir --disable-output
// RUN: %{bmc} %t.dir/w5.hw.mlir --module=RippleAdder_width5_CheckContract_0 | FileCheck %s --check-prefix=BMC

// RUN: %{test} config %t.dir/w32.json --width 32
// RUN: cd %t.dir && %{test} design %t.dir/w32.json
// RUN: firtool %t.dir/RippleAdder_width32.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t.dir/w32.hw.mlir --disable-output
// RUN: %{bmc} %t.dir/w32.hw.mlir --module=RippleAdder_width32_CheckContract_0 | FileCheck %s --check-prefix=BMC
// RUN: rm -rf %t.dir

// CONFIG: {"width":5}

// STRUCTURE-LABEL: firrtl.module @RippleAdder_width5
// STRUCTURE-NOT: firrtl.add
// STRUCTURE: firrtl.xor
// STRUCTURE: firrtl.and
// STRUCTURE: firrtl.or
// STRUCTURE: firrtl.contract
// STRUCTURE: firrtl.int.verif.ensure

// VERILOG-LABEL: module RippleAdder_width5(
// VERILOG: input{{ +}}[4:0]{{ +}}a,
// VERILOG: input{{ +}}ci,
// VERILOG: output{{ +}}co,
// VERILOG: output{{ +}}[4:0]{{ +}}sum

// BMC: Bound reached with no violations!
