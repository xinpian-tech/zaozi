// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.BrentKungAdder" %s --
// DEFINE: %{bmc} = circt-bmc %t-w8.clean.mlir --module=BrentKungAdder_width8_radix2_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run

// width 8, radix 2
// RUN: %{test} config %t-w8.json --width 8 --radix 2
// RUN: FileCheck %s -check-prefix=CONFIG8 --input-file=%t-w8.json
// RUN: %{test} design %t-w8.json
// RUN: circt-opt BrentKungAdder_width8_radix2.mlirbc | FileCheck %s -check-prefix=CONTRACT8
// RUN: firtool BrentKungAdder_width8_radix2.mlirbc | FileCheck %s -check-prefix=VERILOG8
// RUN: firtool BrentKungAdder_width8_radix2.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w8.contract.hw.mlir --disable-output
// RUN: FileCheck %s -check-prefix=LOWERED8 --input-file=%t-w8.contract.hw.mlir
// RUN: printf 'module {\n' > %t-w8.formal.mlir
// RUN: sed -n '/^  verif.formal @BrentKungAdder_width8_radix2_CheckContract_0/,/^  }/p' %t-w8.contract.hw.mlir >> %t-w8.formal.mlir
// RUN: printf '}\n' >> %t-w8.formal.mlir
// RUN: circt-opt %t-w8.formal.mlir --prepare-for-formal --hw-cleanup --canonicalize --cse -o %t-w8.clean.mlir
// RUN: FileCheck %s -check-prefix=FORMAL8 --input-file=%t-w8.clean.mlir
// RUN: %{bmc} | FileCheck %s -check-prefix=BMC8
// RUN: rm %t-w8.json %t-w8.contract.hw.mlir %t-w8.formal.mlir %t-w8.clean.mlir BrentKungAdder_width8_radix2.mlirbc -f

// width 32, radix 8
// RUN: %{test} config %t-w32r8.json --width 32 --radix 8
// RUN: FileCheck %s -check-prefix=CONFIG32R8 --input-file=%t-w32r8.json
// RUN: %{test} design %t-w32r8.json
// RUN: firtool BrentKungAdder_width32_radix8.mlirbc > %t-r8.sv
// RUN: FileCheck %s -check-prefix=VERILOG32R8 --input-file=%t-r8.sv

// width 32, radix 4
// RUN: %{test} config %t-w32r4.json --width 32 --radix 4
// RUN: FileCheck %s -check-prefix=CONFIG32R4 --input-file=%t-w32r4.json
// RUN: %{test} design %t-w32r4.json
// RUN: firtool BrentKungAdder_width32_radix4.mlirbc > %t-r4.sv
// RUN: FileCheck %s -check-prefix=VERILOG32R4 --input-file=%t-r4.sv

// radix must change the RTL: the two width-32 designs (radix 8 vs 4) differ.
// `not diff` passes iff the files are NOT identical.
// RUN: not diff -q %t-r8.sv %t-r4.sv >/dev/null
// RUN: rm %t-w32r8.json %t-w32r4.json %t-r8.sv %t-r4.sv BrentKungAdder_width32_radix8.mlirbc BrentKungAdder_width32_radix4.mlirbc -f

// CONFIG8: {"width":8,"radix":2}

// CONTRACT8: firrtl.contract
// CONTRACT8: firrtl.int.verif.ensure

// VERILOG8-LABEL: module BrentKungAdder_width8_radix2
// VERILOG8: input{{ +}}[7:0]{{ +}}A,
// VERILOG8: input{{ +}}CI,
// VERILOG8: output{{ +}}CO,
// VERILOG8: output{{ +}}[7:0]{{ +}}SUM

// LOWERED8-LABEL: hw.module @BrentKungAdder_width8_radix2
// LOWERED8: verif.assume
// LOWERED8-SAME: prefix_adder_matches_add
// LOWERED8-LABEL: verif.formal @BrentKungAdder_width8_radix2_CheckContract_0
// LOWERED8: verif.assert
// LOWERED8-SAME: prefix_adder_matches_add

// FORMAL8: verif.formal @BrentKungAdder_width8_radix2_CheckContract_0
// FORMAL8: verif.assert
// FORMAL8-SAME: prefix_adder_matches_add

// BMC8: Bound reached with no violations!

// CONFIG32R8: {"width":32,"radix":8}

// VERILOG32R8-LABEL: module BrentKungAdder_width32_radix8
// VERILOG32R8: input{{ +}}[31:0]{{ +}}A,
// VERILOG32R8: input{{ +}}CI,
// VERILOG32R8: output{{ +}}CO,
// VERILOG32R8: output{{ +}}[31:0]{{ +}}SUM
// The radix-8 group-propagate fold emits wide AND-chains.
// VERILOG32R8: {{.+ & .+ & .+ & .+ & .+ & .+ & .+ & .+}}

// CONFIG32R4: {"width":32,"radix":4}

// VERILOG32R4-LABEL: module BrentKungAdder_width32_radix4
// VERILOG32R4: input{{ +}}[31:0]{{ +}}A,
// VERILOG32R4: input{{ +}}CI,
// VERILOG32R4: output{{ +}}CO,
// VERILOG32R4: output{{ +}}[31:0]{{ +}}SUM
