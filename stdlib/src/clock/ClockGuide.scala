// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

/** Without `cell` the guide is the clock Buffer role on its `a` and `outClock` pins. */
case class ClockGuideParameter(
  cell:     Option[String] = None,
  input:    String = "a",
  output:   String = "outClock",
  instance: Option[String] = None)
    extends Parameter:
  require(
    cell.forall(_.nonEmpty) && Seq(input, output).forall(_.nonEmpty),
    "clock guide cell and pins must not be empty"
  )
  require(cell.nonEmpty || (input == "a" && output == "outClock"), "clock guide pins need a cell")
  require(input != output, "clock guide pins must be distinct")
  require(instance.forall(_.nonEmpty), "clock guide instance must not be empty")

given upickle.default.ReadWriter[ClockGuideParameter] = upickle.default.macroRW

class ClockGuideIO(parameter: ClockGuideParameter) extends HWRecord(parameter):
  Flipped(parameter.input, Clock())
  Aligned(parameter.output, Clock())

class ClockGuideLayers(parameter: ClockGuideParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ClockGuideProbe(parameter: ClockGuideParameter) extends DVBundle[ClockGuideParameter, ClockGuideLayers](parameter)

case class ClockGuideVerilogParameter() extends VerilogParameter

@generator
object ClockGuide
    extends VerilogWrapper[
      ClockGuideParameter,
      ClockGuideLayers,
      ClockGuideIO,
      ClockGuideProbe,
      ClockGuideVerilogParameter
    ]:
  override def moduleName(parameter: ClockGuideParameter): String =
    s"ClockGuide_${(parameter.cell, parameter.input, parameter.output).hashCode.toHexString}"
  def verilogModuleName(parameter: ClockGuideParameter) = parameter.cell.get
  def verilogParameter(parameter:  ClockGuideParameter) = ClockGuideVerilogParameter()
