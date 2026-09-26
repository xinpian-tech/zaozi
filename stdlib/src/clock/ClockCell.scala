// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

enum ClockCellKind derives upickle.default.ReadWriter:
  case Buffer, Inverter, Or, Mux, Xor, GatePositive, GateNegative

given mainargs.TokensReader.Simple[ClockCellKind]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, ClockCellKind] = Right(ClockCellKind.valueOf(strs.head))

case class ClockCellParameter(kind: ClockCellKind) extends Parameter:
  val binary = Set(ClockCellKind.Or, ClockCellKind.Mux, ClockCellKind.Xor)(kind)
  val gate   = Set(ClockCellKind.GatePositive, ClockCellKind.GateNegative)(kind)

given upickle.default.ReadWriter[ClockCellParameter] = upickle.default.macroRW

class ClockCellIO(parameter: ClockCellParameter) extends HWBundle(parameter):
  val a        = Flipped(Clock())
  val b        = Option.when(parameter.binary)(Flipped(Clock()))
  val select   = Option.when(parameter.kind == ClockCellKind.Mux)(Flipped(Bool()))
  val enable   = Option.when(parameter.gate)(Flipped(Bool()))
  val outClock = Aligned(Clock())

class ClockCellLayers(parameter: ClockCellParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ClockCellProbe(parameter: ClockCellParameter) extends DVBundle[ClockCellParameter, ClockCellLayers](parameter)

case class ClockCellVerilogParameter() extends VerilogParameter

@generator
object ClockCell
    extends VerilogWrapper[ClockCellParameter, ClockCellLayers, ClockCellIO, ClockCellProbe, ClockCellVerilogParameter]:
  override def moduleName(parameter:     ClockCellParameter): String              = verilogModuleName(parameter)
  def verilogModuleName(parameter:       ClockCellParameter) = s"ZaoziClock${parameter.kind}"
  def verilogParameter(parameter:        ClockCellParameter) = ClockCellVerilogParameter()
  override def verilogSources(parameter: ClockCellParameter): Map[String, String] = Map("ClockCells.sv" -> source)
  def source:                                                 String              = os.read(os.resource / "clock" / "ClockCells.sv")
