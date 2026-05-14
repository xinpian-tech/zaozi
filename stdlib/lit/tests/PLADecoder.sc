// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.default.PLADecoder" --
// RUN: %{test} config %t.json --name "ALU" --tables "[alu][default][add:01,sub:10,default:00][00:add,01:sub]"
// RUN: FileCheck %s -check-prefix=CONFIG --input-file=%t.json
// RUN: %{test} design %t.json
// RUN: firtool PLA*.mlirbc | FileCheck %s -check-prefix=VERILOG
// RUN: rm %t.json *.mlirbc -f

// CONFIG: {"name":"ALU","tables":["[alu][default][add:01,default:00,sub:10][00:add,01:sub]"]}
// VERILOG-LABEL: module PLA_ALU
// VERILOG: input [1:0] instruction
// VERILOG: output [1:0] output_alu
// VERILOG: assign output_alu = {instruction[0] & ~(instruction[1]), instruction == 2'h0}
