// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class PadTop %s --
// RUN: mkdir -p %t.dir
// RUN: %{test} config %t.dir/config.json
// RUN: cd %t.dir && %{test} design %t.dir/config.json
// RUN: firld --base-circuit=PadTop %t.dir/*.mlirbc | firtool --format=mlir --strip-debug-info > %t.dir/pad.sv
// RUN: FileCheck %s --input-file=%t.dir/pad.sv

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class PadParameter() extends Parameter
given upickle.default.ReadWriter[PadParameter] = upickle.default.macroRW

class PadLayers(parameter: PadParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class PadIO(parameter: PadParameter) extends HWBundle(parameter):
  val PAD = Inout(Bits(1))
  val A = Flipped(Bool())
  val Y = Aligned(Bool())
  val IE = Flipped(Bool())
  val OE = Flipped(Bool())
  val CS = Flipped(Bool())
  val SL = Flipped(Bool())
  val PU = Flipped(Bool())
  val PD = Flipped(Bool())
  val PDRV0 = Flipped(Bool())
  val PDRV1 = Flipped(Bool())

class PadProbe(parameter: PadParameter) extends DVBundle[PadParameter, PadLayers](parameter)
case class PadVerilogParameter() extends VerilogParameter

// GF180MCU bi_t non-PG blackbox view at 40cdef6d74ec5c9b7d596c147df94c98366afc5b.
@generator
object GF180InOut extends VerilogWrapper[PadParameter, PadLayers, PadIO, PadProbe, PadVerilogParameter]:
  def verilogModuleName(parameter: PadParameter) = "gf180mcu_fd_io__bi_t"
  def verilogParameter(parameter: PadParameter) = PadVerilogParameter()

@generator
object PadChild extends Generator[PadParameter, PadLayers, PadIO, PadProbe]:
  override def moduleName(parameter: PadParameter): String = "PadChild"
  def architecture(parameter: PadParameter) =
    val io = summon[Interface[PadIO]]
    val cell = GF180InOut.instantiate(parameter)
    val padWire = Wire(io.PAD.getType)
    attach(io.PAD, padWire, cell.io.PAD)
    Seq("A", "IE", "OE", "CS", "SL", "PU", "PD", "PDRV0", "PDRV1").foreach: name =>
      cell.io.field[Bool](name) := io.field[Bool](name)
    io.Y := cell.io.Y

@generator
object PadTop extends Generator[PadParameter, PadLayers, PadIO, PadProbe]:
  override def moduleName(parameter: PadParameter): String = "PadTop"
  def architecture(parameter: PadParameter) =
    val io = summon[Interface[PadIO]]
    val child = PadChild.instantiate(parameter)
    attach(io.PAD, child.io.PAD)
    Seq("A", "IE", "OE", "CS", "SL", "PU", "PD", "PDRV0", "PDRV1").foreach: name =>
      child.io.field[Bool](name) := io.field[Bool](name)
    io.Y := child.io.Y

// CHECK-LABEL: module PadChild(
// CHECK:       inout {{.*}}PAD,
// CHECK:       gf180mcu_fd_io__bi_t cell_0 (
// CHECK:         .PAD{{ *}}(PAD),
// CHECK-LABEL: module PadTop(
// CHECK:       inout {{.*}}PAD,
// CHECK:       PadChild child (
// CHECK:         .PAD{{ *}}(PAD),
