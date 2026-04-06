// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" --java-opt="--enable-native-access=ALL-UNNAMED" --java-opt="--enable-preview" --java-opt="-Djava.library.path=%JAVALIBRARYPATH" %s --
// RUN: rm -rf %t && mkdir -p %t && cd %t
// RUN: %{test} config %t/config.json --width 32
// RUN: %{test} design %t/config.json
// RUN: firtool %t/Passthrough*.mlirbc | FileCheck %s -check-prefix=VERILOG
// RUN: rm -rf %t

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.default.{*, given}

import java.lang.foreign.Arena

case class PassthroughParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[PassthroughParameter] = upickle.default.macroRW

class PassthroughLayers(parameter: PassthroughParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class PassthroughIO(parameter: PassthroughParameter) extends HWBundle(parameter):
  val i = Flipped(UInt(parameter.width))
  val o = Aligned(UInt(parameter.width))

class PassthroughProbe(parameter: PassthroughParameter)
    extends DVBundle[PassthroughParameter, PassthroughLayers](parameter)

@generator
object PassthroughModule extends Generator[PassthroughParameter, PassthroughLayers, PassthroughIO, PassthroughProbe]:
  // VERILOG-LABEL: module PassthroughModule_e88425e0(
  def architecture(parameter: PassthroughParameter) =
    // VERILOG-NEXT:   input  [31:0] i,
    // VERILOG-NEXT:   output [31:0] o
    // VERILOG-NEXT: );
    // VERILOG:   assign o = i;
    val io = summon[Interface[PassthroughIO]]
    io.o := io.i
  // VERILOG: endmodule
