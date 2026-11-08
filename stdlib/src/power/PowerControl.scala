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
  def layers = Seq(Layer("Verification"), Layer("Progress"))

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

      val active       = io.resetN.asBool
      Assert((at(Off) & io.enable).S |=> at(WaitDependency).S, active, "enable_starts_dependency_wait")
      Assert((at(WaitDependency) & !io.enable).S |=> at(Off).S, active, "withdrawn_enable_cancels_wait")
      Assert(
        (at(WaitDependency) & io.enable & io.hardReady & (io.softReady | waitExpired)).S |=> at(TurnOn).S,
        active,
        "dependencies_allow_power_on"
      )
      Assert(
        (at(WaitDependency) & io.enable & !io.hardReady & waitExpired).S |=> (at(Fault) & fault).S,
        active,
        "hard_dependency_timeout_faults"
      )
      Assert(softMiss.S |=> (at(TurnOn) & fault).S, active, "soft_timeout_proceeds_and_records_fault")
      Assert(
        (at(TurnOn) & io.enable & onExpired & io.powerGood).S |=> at(ClockOn).S,
        active,
        "power_good_precedes_clock_on"
      )
      Assert(
        (at(TurnOn) & io.enable & onExpired & !io.powerGood).S |=> (at(Fault) & fault).S,
        active,
        "power_on_timeout_faults"
      )
      Assert((at(TurnOn) & !io.enable).S |=> at(TurnOff).S, active, "power_on_reversal_turns_off")
      Assert(at(ClockOn).S |=> at(On).S, active, "clock_on_precedes_reset_release")
      Assert((at(On) & !io.enable).S |=> at(ResetAssert).S, active, "disable_asserts_reset_first")
      Assert(at(ResetAssert).S |=> at(TurnOff).S, active, "reset_assertion_precedes_power_off")
      Assert((at(TurnOff) & offExpired & !io.powerGood).S |=> at(Off).S, active, "power_off_completes")
      Assert((at(TurnOff) & offExpired & io.powerGood).S |=> (at(Fault) & fault).S, active, "power_off_timeout_faults")
      Assert((at(Fault) & !io.enable).S |=> at(Off).S, active, "fault_disable_returns_off")
      Assert(
        (at(Fault) & io.enable & io.hardReady & waitExpired).S |=> at(WaitDependency).S,
        active,
        "fault_retry_rechecks_dependencies"
      )
      Assert((at(Fault) & io.faultClear).S |=> (!fault).S, active, "fault_clear_acknowledged")
      Assert((fault & !(at(Fault) & io.faultClear)).S |=> fault.S, active, "fault_status_sticky")
      Assert(
        (at(WaitDependency) & io.enable & !waitExpired & (!io.hardReady | !io.softReady)).S |=>
          at(WaitDependency).S,
        active,
        "dependencies_wait_before_deadline"
      )
      Assert((at(TurnOn) & io.enable & !onExpired).S |=> at(TurnOn).S, active, "power_on_settling_is_not_shortened")
      Assert((at(TurnOff) & !offExpired).S |=> at(TurnOff).S, active, "power_off_settling_is_not_shortened")
      Assert(
        (at(Fault) & io.enable & (!io.hardReady | !waitExpired)).S |=> at(Fault).S,
        active,
        "fault_retry_waits_for_dependencies_and_deadline"
      )
      Assert((at(Off) & !io.enable).S |=> at(Off).S, active, "disabled_power_stays_off")
      Assert((at(On) & io.enable).S |=> at(On).S, active, "enabled_power_stays_on")
      val previousWait = RegInit(BigInt(0).U(width))
      val previousOn   = RegInit(BigInt(0).U(width))
      val previousOff  = RegInit(BigInt(0).U(width))
      previousWait := waitCount
      previousOn   := onCount
      previousOff  := offCount
      Assert((at(Off) & io.enable).S |=> (waitCount === waitInitial).S, active, "dependency_deadline_loaded_on_start")
      Assert(
        (at(WaitDependency) & io.enable & io.hardReady & (io.softReady | waitExpired)).S |=>
          (onCount === onInitial).S,
        active,
        "power_on_deadline_loaded"
      )
      Assert(
        (!at(TurnOff) & (next === BigInt(TurnOff.ordinal).B(3))).S |=> (offCount === offInitial).S,
        active,
        "power_off_deadline_loaded"
      )
      val waiting = (at(WaitDependency) & io.enable & (!io.hardReady | !io.softReady)) |
        (at(Fault) & io.enable)
      Assert(
        (waiting & !waitExpired).S |=>
          (waitCount === (previousWait - BigInt(1).U(width)).asBits.tail(1).asUInt).S,
        active,
        "dependency_deadline_advances"
      )
      Assert(
        (at(TurnOn) & io.enable & !onExpired).S |=>
          (onCount === (previousOn - BigInt(1).U(width)).asBits.tail(1).asUInt).S,
        active,
        "power_on_deadline_advances"
      )
      Assert(
        (at(TurnOff) & !offExpired).S |=>
          (offCount === (previousOff - BigInt(1).U(width)).asBits.tail(1).asUInt).S,
        active,
        "power_off_deadline_advances"
      )
      Assert(
        (!io.testEnable | (io.clockEnable & io.resetGateN.asBool & io.ready & io.valid)).I,
        "test_mode_forces_ready"
      )
      Assert(
        (io.testEnable | !io.ready | (io.clockEnable & io.resetGateN.asBool)).I,
        "ready_requires_clock_and_reset_release"
      )
      if !parameter.hasSwitch then Assert((!io.powerSwitch).I, "unswitched_domain_has_no_switch_request")
      Cover(
        at(Off).S.##*(at(WaitDependency).S).##*(at(TurnOn).S).##*(at(ClockOn).S).##*(at(On).S),
        active & !io.testEnable,
        "power_startup_complete"
      )
      Cover(
        at(On).S.##*(at(ResetAssert).S).##*(at(TurnOff).S).##*(at(Off).S),
        active & !io.testEnable,
        "power_shutdown_complete"
      )
      Cover((at(TurnOn) & !io.enable).S.##*(at(Off).S), active, "power_startup_cancelled")
      Cover(at(Fault).S.##*(at(WaitDependency).S).##*(at(On).S), active & !io.testEnable, "power_retry_succeeds")
      Cover(softMiss.S.##*(at(On).S), active & !io.testEnable, "soft_dependency_bypass_completes")

    layer("Progress"):
      given ClockEvent = posedge(io.clock)
      val active       = io.resetN.asBool & !io.testEnable
      Assert(
        always((io.enable & !io.faultClear).S) implies eventually((io.ready | io.fault).S),
        active,
        "power_request_eventually_completes_or_faults"
      )
      Assert(
        always((io.enable & io.hardReady & io.powerGood).S) implies eventually(io.ready.S),
        active,
        "available_power_eventually_ready"
      )
      Assert(
        always((!io.enable & !io.powerGood).S) implies eventually((!io.ready & !io.clockEnable & !io.powerSwitch).S),
        active,
        "power_shutdown_eventually_completes"
      )
