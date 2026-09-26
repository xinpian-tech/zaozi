// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.power

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.Block

given mainargs.TokensReader.Simple[BigInt]:
  def shortName = "cycles"
  def read(strs: Seq[String]): Right[Nothing, BigInt] = Right(BigInt(strs.head))

case class PowerControlParameter(
  hasSwitch:            Boolean,
  waitDependencyCycles: BigInt,
  settleOnCycles:       BigInt,
  settleOffCycles:      BigInt)
    extends Parameter:
  val delays       = Seq(waitDependencyCycles, settleOnCycles, settleOffCycles)
  require(delays.forall(_ >= 0), "power delays must not be negative")
  val counterWidth = (delays.max - 1).max(0).bitLength.max(1)

given upickle.default.ReadWriter[PowerControlParameter] = upickle.default.macroRW

class PowerControlLayers(parameter: PowerControlParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class PowerControlIO(parameter: PowerControlParameter) extends HWBundle(parameter):
  val clock       = Flipped(Clock())
  val resetN      = Flipped(Reset())
  val testEnable  = Flipped(Bool())
  val enable      = Flipped(Bool())
  val faultClear  = Flipped(Bool())
  val hardReady   = Flipped(Bool())
  val softReady   = Flipped(Bool())
  // Checked at the power transition deadline; On does not monitor later power loss.
  val powerGood   = Flipped(Bool())
  val clockEnable = Aligned(Bool())
  val resetGateN  = Aligned(Reset())
  val powerSwitch = Aligned(Bool())
  // Ready precedes any reset receiver attached to resetGateN.
  val ready       = Aligned(Bool())
  val valid       = Aligned(Bool())
  val fault       = Aligned(Bool())

class PowerControlProbe(parameter: PowerControlParameter)
    extends DVBundle[PowerControlParameter, PowerControlLayers](parameter)

private enum PowerPhase:
  case Off, WaitDependency, TurnOn, On, TurnOff, Fault, ClockOn, ResetAssert

@generator
object PowerControl extends Generator[PowerControlParameter, PowerControlLayers, PowerControlIO, PowerControlProbe]:
  def architecture(parameter: PowerControlParameter) =
    import PowerPhase.*
    val io           = summon[Interface[PowerControlIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.asyncActiveLow(io.resetN)
    val phase        = RegInit(BigInt(Off.ordinal).B(3))
    val next         = Wire(Bits(3))
    next  := phase
    phase := next
    def at(
      value: PowerPhase
    )(
      using Block
    ): Referable[Bool] = phase === BigInt(value.ordinal).B(3)
    def go(
      value: PowerPhase
    )(
      using Block
    ): Unit = next := BigInt(value.ordinal).B(3)
    val width = parameter.counterWidth
    val waitCount   = RegInit(BigInt(0).U(width))
    val onCount     = RegInit(BigInt(0).U(width))
    val offCount    = RegInit(BigInt(0).U(width))
    val waitInitial = (parameter.waitDependencyCycles - 1).max(0).U(width)
    val onInitial   = (parameter.settleOnCycles - 1).max(0).U(width)
    val offInitial  = (parameter.settleOffCycles - 1).max(0).U(width)
    val waitExpired = waitCount === BigInt(0).U(width)
    val onExpired   = onCount === BigInt(0).U(width)
    val offExpired  = offCount === BigInt(0).U(width)
    val fault       = RegInit(false.B)

    when(at(Off) & io.enable) {
      go(WaitDependency)
      waitCount := waitInitial
    }
    when(at(WaitDependency)) {
      when(!io.enable) { go(Off) }.otherwise {
        when(io.hardReady & (io.softReady | waitExpired)) {
          go(TurnOn)
          onCount := onInitial
        }.otherwise {
          when(!io.hardReady & waitExpired) {
            go(Fault)
            waitCount := waitInitial
          }.otherwise {
            when(!waitExpired) { waitCount := (waitCount - BigInt(1).U(width)).asBits.tail(1).asUInt }
          }
        }
      }
    }
    when(at(TurnOn)) {
      when(!io.enable) { go(TurnOff) }.otherwise {
        when(onExpired) {
          when(io.powerGood) { go(ClockOn) }.otherwise {
            go(Fault)
            waitCount := waitInitial
          }
        }.otherwise { onCount := (onCount - BigInt(1).U(width)).asBits.tail(1).asUInt }
      }
    }
    when(at(On) & !io.enable) { go(ResetAssert) }
    when(at(ClockOn)) { go(On) }
    when(at(ResetAssert)) { go(TurnOff) }
    when(at(TurnOff)) {
      when(offExpired) {
        when(!io.powerGood) { go(Off) }.otherwise {
          go(Fault)
          waitCount := waitInitial
        }
      }.otherwise { offCount := (offCount - BigInt(1).U(width)).asBits.tail(1).asUInt }
    }
    when(!at(TurnOff) & (next === BigInt(TurnOff.ordinal).B(3))) { offCount := offInitial }
    when(at(Fault)) {
      when(!io.enable) { go(Off) }.otherwise {
        when(io.hardReady & waitExpired) {
          go(WaitDependency)
          waitCount := waitInitial
        }.otherwise {
          when(!waitExpired) { waitCount := (waitCount - BigInt(1).U(width)).asBits.tail(1).asUInt }
        }
      }
    }
    val softMiss = at(WaitDependency) & io.enable & io.hardReady & !io.softReady & waitExpired
    when(softMiss | (next === BigInt(Fault.ordinal).B(3))) { fault := true.B }
    when(at(Fault) & io.faultClear) { fault := false.B }

    val clock    = at(On) | at(ClockOn) | at(ResetAssert) | io.testEnable
    val released = at(On) | io.testEnable
    val power    = parameter.hasSwitch.B & (at(TurnOn) | clock)
    val valid    = at(On) | io.testEnable | ((at(TurnOn) | at(TurnOff) | at(ClockOn) | at(ResetAssert)) & io.powerGood)

    io.powerSwitch := power
    io.clockEnable := clock
    io.resetGateN  := released.asReset
    io.ready       := released
    io.valid       := valid
    io.fault       := fault

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      Assert(
        (!clock & !io.testEnable).S |=> (!released | io.testEnable).S,
        io.resetN.asBool,
        "clock_precedes_reset_release"
      )
      Assert((released & !io.testEnable).S |=> (clock | io.testEnable).S, io.resetN.asBool, "reset_precedes_clock_stop")
      PowerPhase.values.foreach: value =>
        Cover((at(value) & !io.testEnable).S, io.resetN.asBool, s"power_${value.toString.toLowerCase}")
      Cover(softMiss.S, io.resetN.asBool, "soft_dependency_timeout")
      Cover(
        (at(WaitDependency) & io.enable & !io.hardReady & waitExpired).S,
        io.resetN.asBool,
        "hard_dependency_timeout"
      )
      Cover((at(TurnOn) & io.enable & onExpired & !io.powerGood).S, io.resetN.asBool, "power_on_timeout")
      Cover((at(TurnOff) & offExpired & io.powerGood).S, io.resetN.asBool, "power_off_timeout")
      Cover((at(Fault) & io.enable & io.hardReady & waitExpired).S, io.resetN.asBool, "fault_retry")
      Cover((at(Fault) & io.faultClear).S, io.resetN.asBool, "fault_clear")
      Cover((at(On) & !io.powerGood & !io.testEnable).S, io.resetN.asBool, "on_ignores_power_loss")
      Cover(io.testEnable.S, io.resetN.asBool, "test_force_on")
