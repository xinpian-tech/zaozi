// SPDX-License-Identifier: Apache-2.0
// Stand-in for zaozi PR #159's clock/ClockGate.scala with the same interface; #159 maps the gate to a cell of its
// clock cell library, the demo has none and latches the enable itself. Delete once #159 lands.
package me.jiuyang.stdlib.clock

import me.jiuyang.stdlib.default.{SynchronizedReset, SynchronizedResetParameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.dialect.firrtl.operation.RegResetPolarity

// The demo has no clock cell library: #159's `library` field is left out.
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
    val io = summon[Interface[ClockGateIO]]
    // Reset reaches the gate only through its latch, so the output never switches mid-phase.
    val inReset = if parameter.clockDuringReset then
      given ClockScope = ClockScope.posedge(io.clock)
      val receiver = SynchronizedReset.instantiate(SynchronizedResetParameter(2, RegResetPolarity.NegReset))
      receiver.io.clock := io.clock
      receiver.io.reset := io.resetN
      !receiver.io.synchronizedReset.asBool
    else false.B
    val wanted  = io.enable | io.testEnable | inReset
    given ResetScope = ResetScope.asyncActiveLow(io.resetN)
    // A positive gate holds its enable while the clock is high, a negative gate while it is low.
    if parameter.positive then
      val enabled = ClockScope.negedge(io.clock) {
        val latch = RegInit(false.B)
        latch := wanted
        latch
      }
      io.output := (io.clock.asBool & enabled).asClock
    else
      val enabled = ClockScope.posedge(io.clock) {
        val latch = RegInit(false.B)
        latch := wanted
        latch
      }
      io.output := (io.clock.asBool | !enabled).asClock
