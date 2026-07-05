// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.BrentKungAdder" %s --

// width 8, radix 2
// RUN: %{test} config %t-w8.json --width 8 --radix 2
// RUN: FileCheck %s -check-prefix=CONFIG8 --input-file=%t-w8.json
// RUN: %{test} design %t-w8.json
// RUN: firtool BrentKungAdder_width8_radix2.mlirbc | FileCheck %s -check-prefix=VERILOG8
// RUN: rm %t-w8.json BrentKungAdder_width8_radix2.mlirbc -f

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
// RUN: not diff %t-r8.sv %t-r4.sv
// RUN: rm %t-w32r8.json %t-w32r4.json %t-r8.sv %t-r4.sv BrentKungAdder_width32_radix8.mlirbc BrentKungAdder_width32_radix4.mlirbc -f

// CONFIG8: {"width":8,"radix":2}

// VERILOG8-LABEL: module BrentKungAdder_width8_radix2
// VERILOG8: input{{ +}}[7:0]{{ +}}A,
// VERILOG8: input{{ +}}CI,
// VERILOG8: output{{ +}}CO,
// VERILOG8: output{{ +}}[7:0]{{ +}}SUM

// CONFIG32R8: {"width":32,"radix":8}

// VERILOG32R8-LABEL: module BrentKungAdder_width32_radix8
// VERILOG32R8: input{{ +}}[31:0]{{ +}}A,
// VERILOG32R8: input{{ +}}CI,
// VERILOG32R8: output{{ +}}CO,
// VERILOG32R8: output{{ +}}[31:0]{{ +}}SUM
// The radix-8 group-propagate fold emits 8-wide AND-chains.
// VERILOG32R8: {{_GEN_[0-9]+ & _GEN_[0-9]+ & _GEN_[0-9]+ & _GEN_[0-9]+ & _GEN_[0-9]+ & _GEN_[0-9]+ & _GEN_[0-9]+ & _GEN_[0-9]+}}

// CONFIG32R4: {"width":32,"radix":4}

// VERILOG32R4-LABEL: module BrentKungAdder_width32_radix4
// VERILOG32R4: input{{ +}}[31:0]{{ +}}A,
// VERILOG32R4: input{{ +}}CI,
// VERILOG32R4: output{{ +}}CO,
// VERILOG32R4: output{{ +}}[31:0]{{ +}}SUM
