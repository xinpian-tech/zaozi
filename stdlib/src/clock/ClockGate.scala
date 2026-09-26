// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockGateParameter(positive: Boolean, clockDuringReset: Boolean) extends Parameter

given upickle.default.ReadWriter[ClockGateParameter] = upickle.default.macroRW

class ClockGateLayers(parameter: ClockGateParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ClockGateIO(parameter: ClockGateParameter) extends HWBundle(parameter):
  val clock      = Flipped(Clock())
  val resetN     = Flipped(Reset())
  val enable     = Flipped(Bool())
  val testEnable = Flipped(Bool())
  val output     = Aligned(Clock())

class ClockGateProbe(parameter: ClockGateParameter) extends DVBundle[ClockGateParameter, ClockGateLayers](parameter)

@generator
object ClockGate extends Generator[ClockGateParameter, ClockGateLayers, ClockGateIO, ClockGateProbe]:
  override def moduleName(parameter: ClockGateParameter): String =
    s"ClockGate_positive${parameter.positive}_clockDuringReset${parameter.clockDuringReset}"

  def architecture(parameter: ClockGateParameter) =
    val io   = summon[Interface[ClockGateIO]]
    val gate = ClockCell.instantiate(
      ClockCellParameter(if parameter.positive then ClockCellKind.GatePositive else ClockCellKind.GateNegative)
    )
    gate.io.a          := io.clock
    gate.io.enable.get := io.enable | io.testEnable
    val bypass = io.testEnable | (if parameter.clockDuringReset then !io.resetN.asBool else false.B)
    val output = ClockCell.instantiate(ClockCellParameter(ClockCellKind.Mux))
    output.io.a          := gate.io.outClock
    output.io.b.get      := io.clock
    output.io.select.get := bypass
    io.output            := output.io.outClock

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      Cover((io.enable & !bypass).S, true.B, "gate_requested")
      Cover((!io.enable & !bypass).S, true.B, "gate_disable_requested")
      Cover(io.testEnable.S, true.B, "gate_test_bypass")
      if parameter.clockDuringReset then Cover((!io.resetN.asBool).S, true.B, "gate_reset_bypass")
