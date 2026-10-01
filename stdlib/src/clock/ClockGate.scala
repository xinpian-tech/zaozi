// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.stdlib.default.{SynchronizedReset, SynchronizedResetParameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.dialect.firrtl.operation.RegResetPolarity

case class ClockGateParameter(
  positive:         Boolean,
  clockDuringReset: Boolean,
  library:          ClockCellLibrary = ClockCellLibrary())
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
    s"ClockGate_positive${parameter.positive}_clockDuringReset${parameter.clockDuringReset}"

  def architecture(parameter: ClockGateParameter) =
    val io      = summon[Interface[ClockGateIO]]
    val gate    = ClockCell.role(
      if parameter.positive then ClockCellKind.GatePositive else ClockCellKind.GateNegative,
      parameter.library
    )
    // Reset reaches the gate only through its latch, so the output never switches mid-phase.
    val inReset = if parameter.clockDuringReset then
      val receiver = SynchronizedReset.instantiate(SynchronizedResetParameter(2, RegResetPolarity.NegReset))
      receiver.io.clock := io.clock
      receiver.io.reset := io.resetN
      !receiver.io.synchronizedReset.asBool
    else false.B
    gate.io.a := io.clock
    gate.io.enable.get := io.enable | io.testEnable | inReset
    io.output          := gate.io.outClock

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      val forced       = io.testEnable | inReset
      Cover((io.enable & !forced).S, true.B, "gate_requested")
      Cover((!io.enable & !forced).S, true.B, "gate_disable_requested")
      Cover(io.testEnable.S, true.B, "gate_test_enable")
      if parameter.clockDuringReset then Cover(inReset.S, true.B, "gate_reset_enable")
