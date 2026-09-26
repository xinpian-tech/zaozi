// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

case class ClockGuideParameter(cell: String, input: String, output: String, instance: Option[String] = None)
    extends Parameter:
  require(Seq(cell, input, output).forall(_.nonEmpty), "clock guide cell and pins must not be empty")
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
  def verilogModuleName(parameter: ClockGuideParameter) = parameter.cell
  def verilogParameter(parameter:  ClockGuideParameter) = ClockGuideVerilogParameter()
