// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.stdlib.clock.{ClockGate, ClockGateCellParameter, ClockGateParameter, ClockMuxCellParameter, given}
import me.jiuyang.stdlib.default.{instruction, output, PLADecoder, SynchronizedReset, SynchronizedResetParameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.dialect.firrtl.operation.RegResetPolarity
import org.llvm.mlir.scalalib.capi.ir.Block

enum PRCMTarget:
  case Off, Reset, Run

given upickle.default.ReadWriter[PRCMTarget] =
  upickle.default.readwriter[String].bimap[PRCMTarget](_.toString, PRCMTarget.valueOf)

case class PRCMDomainParameter(
  resetStages: Int,
  gate:        ClockGateCellParameter = ClockGateCellParameter(true),
  mux:         ClockMuxCellParameter = ClockMuxCellParameter())
    extends Parameter:
  require(gate.positive, "PRCM requires a positive-phase gate")
  val receiver = SynchronizedResetParameter(resetStages, RegResetPolarity.NegReset)

given upickle.default.ReadWriter[PRCMDomainParameter] = upickle.default.macroRW

class PRCMDomainLayers(parameter: PRCMDomainParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"), Layer("Progress"))

class PRCMDomainIO(parameter: PRCMDomainParameter) extends HWBundle(parameter):
  val clock            = Flipped(Clock())
  val coldResetN       = Flipped(Reset())
  // Target is Off=0, Reset=1 or Run=2; feedback inputs belong to the management clock domain.
  val target           = Flipped(Bits(2))
  val powerGood        = Flipped(Bool())
  val isolationActive  = Flipped(Bool())
  val idle             = Flipped(Bool())
  val serviceFault     = Flipped(Bool())
  val powerRequest     = Aligned(Bool())
  val isolationRequest = Aligned(Bool())
  val quiesceRequest   = Aligned(Bool())
  val domainClock      = Aligned(Clock())
  val domainResetN     = Aligned(Reset())
  val readyOff         = Aligned(Bool())
  val readyReset       = Aligned(Bool())
  val readyRun         = Aligned(Bool())
  val released         = Aligned(Bool())
  val fault            = Aligned(Bool())
  val powerLost        = Aligned(Bool())

class PRCMDomainProbe(parameter: PRCMDomainParameter) extends DVBundle[PRCMDomainParameter, PRCMDomainLayers](parameter)

@generator
object PRCMDomain extends Generator[PRCMDomainParameter, PRCMDomainLayers, PRCMDomainIO, PRCMDomainProbe]:
  override def moduleName(parameter: PRCMDomainParameter): String =
    s"PRCMDomain_resetStages${parameter.resetStages}_${parameter.hashCode.toHexString}"

  def architecture(parameter: PRCMDomainParameter) =
    import PRCMPhase.*
    val io           = summon[Interface[PRCMDomainIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.asyncActiveLow(io.coldResetN)

    val phase     = RegInit(BigInt(Init.ordinal).B(4))
    def at(
      value: PRCMPhase
    )(
      using Block
    ): Referable[Bool] = phase === BigInt(value.ordinal).B(4)
    val targetOff = io.target === BigInt(PRCMTarget.Off.ordinal).B(2)
    val targetRun = io.target === BigInt(PRCMTarget.Run.ordinal).B(2)
    val decoder   = PLADecoder.instantiate(PRCMSequenceDecoder.domainNext)
    val outputs   = PLADecoder.instantiate(PRCMSequenceDecoder.domainControl)
    outputs.io.instruction := phase ## io.powerGood.asBits
    val controls = outputs.io.output.field[Bits]("value")
    phase := decoder.io.output.field[Bits]("value")
    val power     = controls(0)
    val clock     = controls(1)
    val reset     = controls(2)
    val isolation = controls(3)
    val quiesce   = controls(4)
    val fault     = controls(5)
    val powerLost = controls(6)
    val gate      = ClockGate.instantiate(ClockGateParameter(parameter.gate, false, parameter.mux))
    gate.io.clock      := io.clock
    gate.io.resetN     := io.coldResetN
    gate.io.enable     := clock
    gate.io.testEnable := false.B
    val receiver = SynchronizedReset.instantiate(parameter.receiver)
    receiver.io.clock := gate.io.output
    receiver.io.reset := (io.coldResetN.asBool & !reset).asReset
    val resetFeedback = Seq.fill(2)(RegInit(true.B))
    resetFeedback.head := !receiver.io.synchronizedReset.asBool
    resetFeedback.last := resetFeedback.head
    val resetObserved   = resetFeedback.last
    val protectedDomain = resetObserved & io.isolationActive
    decoder.io.instruction := phase ## io.target ## io.serviceFault.asBits ## io.idle.asBits ##
      io.isolationActive.asBits ## resetObserved.asBits ## io.powerGood.asBits

    val readyOff   = at(Off) & !io.powerGood & protectedDomain & io.idle
    val readyReset = at(Reset) & io.powerGood & protectedDomain & io.idle
    val readyRun   = at(Run) & io.powerGood & !resetObserved & !io.isolationActive & !io.idle
    io.powerRequest     := power
    io.isolationRequest := isolation
    io.quiesceRequest   := quiesce
    io.domainClock      := gate.io.output
    io.domainResetN     := receiver.io.synchronizedReset
    io.readyOff         := readyOff
    io.readyReset       := readyReset
    io.readyRun         := readyRun
    io.released         := readyOff | readyReset | (fault & !power & !io.powerGood & protectedDomain & io.idle)
    io.fault            := fault
    io.powerLost        := powerLost

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      Assume(
        (targetOff | (io.target === BigInt(PRCMTarget.Reset.ordinal).B(2)) | targetRun).S,
        io.coldResetN.asBool,
        "legal_target"
      )
      Assert(powerLost.S |=> fault.S, io.coldResetN.asBool, "power_loss_enters_fault")
      Assert(
        (!reset & !receiver.io.synchronizedReset.asBool & !fault & !powerLost & !io.serviceFault).S |=>
          (!reset | receiver.io.synchronizedReset.asBool | fault | powerLost | io.serviceFault).S,
        io.coldResetN.asBool,
        "reset_release_is_held_until_receiver"
      )
      val enabled      = io.coldResetN.asBool
      (0 until 4).foreach:                                                                   bit =>
        val next = decoder.io.output.field[Bits]("value").bit(bit)
        Assert(next.S |=> phase.bit(bit).S, enabled, s"phase_transition_${bit}_one")
        Assert((!next).S |=> (!phase.bit(bit)).S, enabled, s"phase_transition_${bit}_zero")
      Assert((io.powerRequest === power).I, "power_request_port_matches_control")
      Assert((io.isolationRequest === isolation).I, "isolation_request_port_matches_control")
      Assert((io.quiesceRequest === quiesce).I, "quiesce_request_port_matches_control")
      Assert((io.readyOff === readyOff).I, "off_completion_port")
      Assert((io.readyReset === readyReset).I, "reset_completion_port")
      Assert((io.readyRun === readyRun).I, "run_completion_port")
      Assert((io.fault === fault).I, "fault_status_port")
      Assert((io.powerLost === powerLost).I, "power_loss_status_port")
      Assert(
        (io.released === (readyOff | readyReset |
          (fault & !power & !io.powerGood & resetObserved & io.isolationActive & io.idle))).S,
        enabled,
        "service_release_matches_safe_terminal_state"
      )
      Assert(
        (io.coldResetN.asBool | (!io.readyOff & !io.readyReset & !io.readyRun)).I,
        "cold_reset_has_no_completed_target"
      )
      Assert(
        (!receiver.io.synchronizedReset.asBool).S |=> resetFeedback.head.S,
        enabled,
        "receiver_assertion_enters_feedback"
      )
      Assert(
        receiver.io.synchronizedReset.asBool.S |=> (!resetFeedback.head).S,
        enabled,
        "receiver_release_enters_feedback"
      )
      Assert(resetFeedback.head.S |=> resetObserved.S, enabled, "reset_feedback_assertion_second_stage")
      Assert((!resetFeedback.head).S |=> (!resetObserved).S, enabled, "reset_feedback_release_second_stage")
      Assert(
        (!clock | !power).S |-> (!receiver.io.synchronizedReset.asBool).S,
        enabled,
        "stopped_domain_receiver_is_asserted"
      )
      // Fault entry deliberately bypasses the normal power-down handshake.
      // Qualify only normal sequencing; the fault properties below stay unconditional.
      // Isolation and idle are acknowledgements: once settled they cannot change
      // spontaneously. Power-good is deliberately excluded so power loss is checked.
      Seq((isolation, io.isolationActive, "isolation"), (quiesce, io.idle, "idle")).foreach:
        (request, feedback, name) =>
          Assume((request & feedback).S |=> (feedback | !request).S, enabled, s"${name}_ack_stays_asserted")
          Assume((!request & !feedback).S |=> (!feedback | request).S, enabled, s"${name}_ack_stays_released")
      val normal = !fault & !powerLost & !io.serviceFault
      Assert(normal.S |=> (!fault).S, enabled, "no_spurious_fault")
      Seq((power, io.powerGood, "power"), (isolation, io.isolationActive, "isolation"), (quiesce, io.idle, "quiesce"))
        .foreach: (request, feedback, name) =>
          Assert((normal & request & !feedback).S |=> request.S, enabled, s"${name}_assertion_waits_for_feedback")
          Assert((normal & !request & feedback).S |=> (!request).S, enabled, s"${name}_release_waits_for_feedback")
      Assert(
        (normal & power & (!protectedDomain | clock)).S |=> power.S,
        enabled,
        "power_off_requires_reset_isolation_and_stopped_clock"
      )
      Assert((normal & clock & !protectedDomain).S |=> clock.S, enabled, "clock_off_requires_reset_and_isolation")
      Assert(
        (normal & reset & (!io.powerGood | !clock | !io.isolationActive)).S |=> reset.S,
        enabled,
        "reset_release_requires_power_clock_and_isolation"
      )
      Assert(
        (normal & isolation & (!io.powerGood | !clock | resetObserved)).S |=> isolation.S,
        enabled,
        "isolation_release_requires_running_receiver"
      )
      Assert(
        (normal & !isolation & (!quiesce | !io.idle)).S |=> (!isolation).S,
        enabled,
        "isolation_waits_for_quiesce_and_idle"
      )
      Assert(
        (normal & quiesce & (!io.powerGood | resetObserved | io.isolationActive)).S |=> quiesce.S,
        enabled,
        "admission_requires_power_reset_release_and_connection"
      )
      Assert((io.serviceFault & !fault).S |=> fault.S, enabled, "service_loss_enters_fault")
      Assert(fault.S |-> (reset & !clock & isolation).S, enabled, "fault_protects_domain")
      Assert(
        (fault & power & (!protectedDomain | !io.idle)).S |=> power.S,
        enabled,
        "fault_shutdown_waits_for_protection"
      )
      Assert(
        (fault & (!targetOff | io.powerGood | !protectedDomain)).S |=> fault.S,
        enabled,
        "fault_recovery_requires_off_and_protection"
      )
      Assert(
        (readyOff === (at(Off) & !io.powerGood & resetObserved & io.isolationActive & io.idle)).S,
        enabled,
        "off_completion"
      )
      Assert(
        (readyReset === (at(Reset) & io.powerGood & resetObserved & io.isolationActive & io.idle)).S,
        enabled,
        "reset_completion"
      )
      Assert(
        (readyRun === (at(Run) & io.powerGood & !resetObserved & !io.isolationActive & !io.idle)).S,
        enabled,
        "run_completion"
      )
      Assert(
        io.released.S |-> (resetObserved & io.isolationActive & io.idle).S,
        enabled,
        "service_release_requires_protection"
      )
      Assert((normal & readyOff & targetOff).S |=> at(Off).S, enabled, "off_is_stable")
      Assert(
        (normal & readyReset & (io.target === BigInt(PRCMTarget.Reset.ordinal).B(2))).S |=> at(Reset).S,
        enabled,
        "reset_is_stable"
      )
      Assert((normal & readyRun & targetRun).S |=> at(Run).S, enabled, "run_is_stable")
      Assert(
        (reset | !io.coldResetN.asBool).I |-> (!io.domainResetN.asBool).I,
        true.B,
        "receiver_asserts_with_reset_request"
      )
      Assert(
        (!io.coldResetN.asBool).I |-> (!power & !clock & reset & isolation & quiesce).I,
        true.B,
        "cold_reset_is_safe"
      )
      Cover(readyOff.S, io.coldResetN.asBool, "domain_off")
      Cover(readyReset.S, io.coldResetN.asBool, "domain_reset")
      Cover(readyRun.S, io.coldResetN.asBool, "domain_run")
      Cover((at(Power) & targetOff & !io.powerGood).S, io.coldResetN.asBool, "power_request_reversed")
      Cover((at(Release) & !targetRun & resetObserved).S, io.coldResetN.asBool, "reset_release_reversed")
      Cover((at(Resume) & !targetRun & io.idle).S, io.coldResetN.asBool, "quiesce_return_reversed")
      Cover(powerLost.S, io.coldResetN.asBool, "domain_power_lost")
      Cover((io.serviceFault & !fault).S, io.coldResetN.asBool, "domain_service_lost")

      // Cover architectural transitions and complete target reversals, not just state visits.
      PRCMPhase.values.foreach: source =>
        val successors = (for
          target   <- PRCMTarget.values.toSeq
          feedback <- 0 until 16
          failed   <- Seq(false, true)
        yield PRCMSequence.step(source, target, PRCMFeedback.fromBits(feedback), failed)).distinct
        successors
          .filter(_ != source)
          .foreach: destination =>
            Cover(at(source).S ### at(destination).S, enabled, s"phase_${source}_to_$destination")
      val completions = Seq((PRCMTarget.Off, readyOff), (PRCMTarget.Reset, readyReset), (PRCMTarget.Run, readyRun))
      completions.foreach:      (source, before) =>
        completions
          .filter(_._1 != source)
          .foreach: (destination, after) =>
            Cover(
              (before & (io.target === BigInt(destination.ordinal).B(2))).S.##*(after.S),
              enabled,
              s"target_${source}_to_$destination"
            )
      Cover(powerLost.S ### fault.S.##*(io.released.S), enabled, "power_loss_to_safe_release")
      Cover((io.serviceFault & !fault).S ### fault.S.##*(io.released.S), enabled, "service_loss_to_safe_release")
      Cover(fault.S.##*(readyOff.S).##*(readyRun.S), enabled, "fault_recovery_and_restart")
      Cover((!io.coldResetN.asBool).S.##*((io.coldResetN.asBool & readyOff).S), true.B, "cold_reset_to_off")

    // Unbounded obligations are kept separate from bounded safety checking.
    // Fairness is a premise of each property, never an assumption hiding fault paths.
    layer("Progress"):
      given ClockEvent     = posedge(io.clock)
      val feedbackResponds =
        Seq(power === io.powerGood, isolation === io.isolationActive, quiesce === io.idle)
          .map(value => always(eventually(value.S)))
          .reduce(_ & _)
      val healthy          = always((!io.serviceFault & !powerLost).S)
      Seq((PRCMTarget.Off, readyOff), (PRCMTarget.Reset, readyReset), (PRCMTarget.Run, readyRun)).foreach:
        (target, ready) =>
          val stableTarget = always((io.target === BigInt(target.ordinal).B(2)).S)
          Assert(
            ((!fault).S & stableTarget & healthy & feedbackResponds) implies eventually(ready.S),
            io.coldResetN.asBool,
            s"${target.toString.toLowerCase}_eventually_ready"
          )

      // Reset feedback is internal: the receiver must progress on the enabled clock.
      Assert(
        always((!reset & clock).S) implies eventually((!resetObserved).S),
        io.coldResetN.asBool,
        "enabled_receiver_eventually_releases"
      )
      Assert(
        always(reset.S) implies eventually(resetObserved.S),
        io.coldResetN.asBool,
        "asserted_receiver_eventually_observed"
      )
      // Persistent service faults may prevent recovery, but must not prevent releasing resources.
      val offRequested = always(targetOff.S)
      Assert(
        (offRequested & feedbackResponds) implies eventually(io.released.S),
        io.coldResetN.asBool,
        "fault_eventually_releases_services"
      )
      Assert(
        (offRequested & feedbackResponds & always((!io.serviceFault).S)) implies eventually(readyOff.S),
        io.coldResetN.asBool,
        "fault_eventually_recovers_off"
      )
