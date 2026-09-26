// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.reset

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ResetCounterParameter(cycles: Int) extends Parameter:
  require(cycles >= 1, s"reset counter cycles must be positive: $cycles")
  val countWidth = BigInt(cycles - 1).bitLength.max(1)

given upickle.default.ReadWriter[ResetCounterParameter] = upickle.default.macroRW

class ResetCounterLayers(parameter: ResetCounterParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ResetCounterIO(parameter: ResetCounterParameter) extends HWBundle(parameter):
  val clock      = Flipped(Clock())
  val resetN     = Flipped(Reset())
  val testEnable = Flipped(Bool())
  val output     = Aligned(Reset())

class ResetCounterProbe(parameter: ResetCounterParameter)
    extends DVBundle[ResetCounterParameter, ResetCounterLayers](parameter)

@generator
object ResetCounter extends Generator[ResetCounterParameter, ResetCounterLayers, ResetCounterIO, ResetCounterProbe]:
  override def moduleName(parameter: ResetCounterParameter): String =
    s"ResetCounter_cycles${parameter.cycles}"

  def architecture(parameter: ResetCounterParameter) =
    val io           = summon[Interface[ResetCounterIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.asyncActiveLow(io.resetN)
    val count        = RegInit(BigInt(0).U(parameter.countWidth))
    val released     = RegInit(false.B)
    val last         = count === BigInt(parameter.cycles - 1).U(parameter.countWidth)
    when(!released) {
      when(last) { released := true.B }.otherwise {
        count := (count + BigInt(1).U(parameter.countWidth)).asBits.tail(1).asUInt
      }
    }
    val output       = io.testEnable ? (io.resetN.asBool, released)
    io.output := output.asReset

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      Assert((last & !released).S |=> released.S, io.resetN.asBool, "counter_releases_at_terminal_count")
      Assert(released.S |=> released.S, io.resetN.asBool, "counter_stays_released")
      Assert(true.B.S.##(parameter.cycles)(true.B.S) |-> released.S, io.resetN.asBool, "counter_release_bound")
      (0 until parameter.cycles).foreach: cycle =>
        val heldDeasserted = io.resetN.asBool throughout true.B.S.##(cycle)(true.B.S)
        Assert(((!io.resetN.asBool).S ### heldDeasserted) |-> (!released).S, s"counter_not_released_early_$cycle")
      Assert((io.resetN.asBool | !io.output.asBool).I, "counter_async_assertion")
      Assert((!io.testEnable | (io.output.asBool === io.resetN.asBool)).I, "counter_test_bypass")
      Cover((!released & io.resetN.asBool & !io.testEnable).S, true.B, "counter_release_pending")
      Cover((released & !io.testEnable).S, true.B, "counter_released")
      Cover(io.testEnable.S, true.B, "counter_test_bypass")

      Cover((!io.resetN.asBool).S ### io.resetN.asBool.S.##*(io.output.asBool.S), true.B, "reset_release_sequence")
      Cover(
        io.output.asBool.S.##*((!io.resetN.asBool).S).##*((!io.output.asBool).S),
        true.B,
        "reset_reassertion_sequence"
      )
