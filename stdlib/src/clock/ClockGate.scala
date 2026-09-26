// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockGateParameter(
  gate:             ClockGateCellParameter,
  clockDuringReset: Boolean,
  mux:              ClockMuxCellParameter = ClockMuxCellParameter())
    extends Parameter

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
    s"ClockGate_positive${parameter.gate.positive}_clockDuringReset${parameter.clockDuringReset}_${parameter.hashCode.toHexString}"

  def architecture(parameter: ClockGateParameter) =
    val io   = summon[Interface[ClockGateIO]]
    val gate = ClockGateCell.instantiate(parameter.gate)
    gate.io.a      := io.clock
    gate.io.enable := io.enable | io.testEnable
    val bypass = io.testEnable | (if parameter.clockDuringReset then !io.resetN.asBool else false.B)
    val output = ClockMuxCell.instantiate(parameter.mux)
    output.io.a      := gate.io.outClock
    output.io.b      := io.clock
    output.io.select := bypass
    io.output        := output.io.outClock

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      Assert((!bypass | (io.output.asBool === io.clock.asBool)).I, "gate_bypass_follows_input")
      Assert((bypass | (io.output.asBool === gate.io.outClock.asBool)).I, "gate_normal_path")
      Cover((io.enable & !bypass).S, true.B, "gate_requested")
      Cover((!io.enable & !bypass).S, true.B, "gate_disable_requested")
      Cover(io.testEnable.S, true.B, "gate_test_bypass")
      if parameter.clockDuringReset then Cover((!io.resetN.asBool).S, true.B, "gate_reset_bypass")
