// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.adder.default.BrentKungAdder" %s --
// DEFINE: %{bmc} = circt-bmc %t.dir/w8.contract.hw.mlir --module=BrentKungAdder_width8_radix2_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run

// width 8, radix 2
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/w8.json --width 8 --radix 2
// RUN: FileCheck %s -check-prefix=CONFIG8 --input-file=%t.dir/w8.json
// RUN: cd %t.dir && %{test} design %t.dir/w8.json
// RUN: circt-opt %t.dir/BrentKungAdder_width8_radix2.mlirbc | FileCheck %s -check-prefix=CONTRACT8
// RUN: firtool %t.dir/BrentKungAdder_width8_radix2.mlirbc | FileCheck %s -check-prefix=VERILOG8
// RUN: firtool %t.dir/BrentKungAdder_width8_radix2.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t.dir/w8.contract.hw.mlir --disable-output
// RUN: FileCheck %s -check-prefix=LOWERED8 --input-file=%t.dir/w8.contract.hw.mlir
// RUN: %{bmc} | FileCheck %s -check-prefix=BMC8

// width 32, radix 8
// RUN: %{test} config %t.dir/w32r8.json --width 32 --radix 8
// RUN: FileCheck %s -check-prefix=CONFIG32R8 --input-file=%t.dir/w32r8.json
// RUN: cd %t.dir && %{test} design %t.dir/w32r8.json
// RUN: firtool %t.dir/BrentKungAdder_width32_radix8.mlirbc > %t.dir/r8.sv
// RUN: FileCheck %s -check-prefix=VERILOG32R8 --input-file=%t.dir/r8.sv

// width 32, radix 4
// RUN: %{test} config %t.dir/w32r4.json --width 32 --radix 4
// RUN: FileCheck %s -check-prefix=CONFIG32R4 --input-file=%t.dir/w32r4.json
// RUN: cd %t.dir && %{test} design %t.dir/w32r4.json
// RUN: firtool %t.dir/BrentKungAdder_width32_radix4.mlirbc > %t.dir/r4.sv
// RUN: FileCheck %s -check-prefix=VERILOG32R4 --input-file=%t.dir/r4.sv

// radix must change the RTL: the two width-32 designs (radix 8 vs 4) differ.
// `not diff` passes iff the files are NOT identical.
// RUN: not diff -q %t.dir/r8.sv %t.dir/r4.sv >/dev/null
// RUN: rm -rf %t.dir

// CONFIG8: {"width":8,"radix":2}

// CONTRACT8: firrtl.contract
// CONTRACT8: firrtl.int.verif.ensure

// VERILOG8-LABEL: module BrentKungAdder_width8_radix2
// VERILOG8: input{{ +}}[7:0]{{ +}}a,
// VERILOG8: input{{ +}}ci,
// VERILOG8: output{{ +}}co,
// VERILOG8: output{{ +}}[7:0]{{ +}}sum

// LOWERED8-LABEL: hw.module @BrentKungAdder_width8_radix2
// LOWERED8: verif.assume
// LOWERED8-SAME: prefix_adder_matches_add
// LOWERED8-LABEL: verif.formal @BrentKungAdder_width8_radix2_CheckContract_0
// LOWERED8: verif.assert
// LOWERED8-SAME: prefix_adder_matches_add

// BMC8: Bound reached with no violations!

// CONFIG32R8: {"width":32,"radix":8}

// VERILOG32R8-LABEL: module BrentKungAdder_width32_radix8
// VERILOG32R8: input{{ +}}[31:0]{{ +}}a,
// VERILOG32R8: input{{ +}}ci,
// VERILOG32R8: output{{ +}}co,
// VERILOG32R8: output{{ +}}[31:0]{{ +}}sum
// The radix-8 group-propagate fold emits wide AND-chains.
// VERILOG32R8: {{.+ & .+ & .+ & .+ & .+ & .+ & .+ & .+}}

// The default radix is omitted from the serialized configuration.
// CONFIG32R4: {"width":32}

// VERILOG32R4-LABEL: module BrentKungAdder_width32_radix4
// VERILOG32R4: input{{ +}}[31:0]{{ +}}a,
// VERILOG32R4: input{{ +}}ci,
// VERILOG32R4: output{{ +}}co,
// VERILOG32R4: output{{ +}}[31:0]{{ +}}sum
