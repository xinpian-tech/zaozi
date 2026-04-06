// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" --java-opt="--enable-native-access=ALL-UNNAMED" --java-opt="--enable-preview" --java-opt="-Djava.library.path=%JAVALIBRARYPATH" %s --
// RUN: rm -rf %t && mkdir -p %t && cd %t
// RUN: %{test} config %t/config.json --input-width 32
// RUN: FileCheck %s -check-prefix=CONFIG --input-file=%t/config.json
// RUN: %{test} design %t/config.json
// RUN: firtool %t/Truncate*.mlirbc | FileCheck %s -check-prefix=VERILOG
// RUN: rm -rf %t

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.default.{*, given}

import java.lang.foreign.Arena

// CONFIG: {"inputWidth":32}
case class TruncateParameter(inputWidth: Int, outputWidth: Int = 16) extends Parameter:
  require(outputWidth <= inputWidth)
given upickle.default.ReadWriter[TruncateParameter] = upickle.default.macroRW

class TruncateLayers(parameter: TruncateParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class TruncateIO(parameter: TruncateParameter) extends HWBundle(parameter):
  val i = Flipped(UInt(parameter.inputWidth))
  val o = Aligned(UInt(parameter.outputWidth))

class TruncateProbe(parameter: TruncateParameter) extends DVBundle[TruncateParameter, TruncateLayers](parameter)

@generator
object Truncate extends Generator[TruncateParameter, TruncateLayers, TruncateIO, TruncateProbe]:
  // VERILOG-LABEL: module Truncate_90db6580(
  def architecture(parameter: TruncateParameter) =
    // VERILOG-NEXT:   input  [31:0] i,
    // VERILOG-NEXT:   output [15:0] o
    // VERILOG-NEXT: );
    // VERILOG:   assign o = i[15:0];
    val io = summon[Interface[TruncateIO]]
    io.o := io.i.asBits.tail(parameter.outputWidth).asUInt
  // VERILOG: endmodule
