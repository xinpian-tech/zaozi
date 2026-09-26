// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class WrapperTop %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/config.json --width 7
// RUN: cd %t.dir && %{test} design %t.dir/config.json
// RUN: firld --base-circuit=WrapperTop %t.dir/*.mlirbc -o %t.dir/top.mlir
// RUN: firtool %t.dir/top.mlir --strip-debug-info -o %t.dir/top.sv
// RUN: FileCheck %s --input-file=%t.dir/top.sv
// RUN: circt-verilog --ir-hw --top=WrapperTop %t.dir/top.sv %t.dir/buffer.sv -o %t.dir/top.hw.mlir
// RUN: rm -rf %t.dir

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class WrapperParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[WrapperParameter] = upickle.default.macroRW

class WrapperLayers(parameter: WrapperParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class WrapperIO(parameter: WrapperParameter) extends HWBundle(parameter):
  val a = Flipped(Bits(parameter.width))
  val b = Aligned(Bits(parameter.width))

class WrapperProbe(parameter: WrapperParameter) extends DVBundle[WrapperParameter, WrapperLayers](parameter)

case class BufferVerilogParameter(WIDTH: Int) extends VerilogParameter

@generator
object BufferCell extends VerilogWrapper[WrapperParameter, WrapperLayers, WrapperIO, WrapperProbe, BufferVerilogParameter]:
  def verilogModuleName(parameter: WrapperParameter) = "ExternalBuffer"
  def verilogParameter(parameter: WrapperParameter) = BufferVerilogParameter(parameter.width)
  override def verilogSources(parameter: WrapperParameter): Map[String, String] = Map("buffer.sv" ->
    "module ExternalBuffer #(parameter WIDTH = 1)(input wire [WIDTH-1:0] a, output wire [WIDTH-1:0] b); assign b = a; endmodule\n")

class WrapperTopIO(parameter: WrapperParameter) extends HWBundle(parameter):
  val a = Flipped(Bits(parameter.width))
  val b = Aligned(Bits(parameter.width))
  val c = Flipped(Bits(parameter.width + 1))
  val d = Aligned(Bits(parameter.width + 1))

@generator
object WrapperTop extends Generator[WrapperParameter, WrapperLayers, WrapperTopIO, WrapperProbe]:
  override def moduleName(parameter: WrapperParameter): String = "WrapperTop"
  def architecture(parameter: WrapperParameter) =
    val io = summon[Interface[WrapperTopIO]]
    val first = BufferCell.instantiate(parameter)
    val second = BufferCell.instantiate(parameter.copy(width = parameter.width + 1))
    first.io.a := io.a
    io.b := first.io.b
    second.io.a := io.c
    io.d := second.io.b

// CHECK-LABEL: module WrapperTop(
// CHECK: ExternalBuffer #(
// CHECK: .WIDTH(7)
// CHECK: ) first (
// CHECK: .a (a),
// CHECK: .b (b)
// CHECK: ExternalBuffer #(
// CHECK: .WIDTH(8)
// CHECK: ) second (
// CHECK: .a (c),
// CHECK: .b (d)
