// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.stdlib.clock.{ClockGate, ClockGateParameter}
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

case class PRCMDomainParameter(resetStages: Int) extends Parameter:
  val receiver = SynchronizedResetParameter(resetStages, RegResetPolarity.NegReset)

given upickle.default.ReadWriter[PRCMDomainParameter] = upickle.default.macroRW

class PRCMDomainLayers(parameter: PRCMDomainParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

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
  override def moduleName(parameter: PRCMDomainParameter): String = s"PRCMDomain_resetStages${parameter.resetStages}"

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
    val gate      = ClockGate.instantiate(ClockGateParameter(true, false))
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
      Assert(
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
      Cover(readyOff.S, io.coldResetN.asBool, "domain_off")
      Cover(readyReset.S, io.coldResetN.asBool, "domain_reset")
      Cover(readyRun.S, io.coldResetN.asBool, "domain_run")
      Cover((at(Power) & targetOff & !io.powerGood).S, io.coldResetN.asBool, "power_request_reversed")
      Cover((at(Release) & !targetRun & resetObserved).S, io.coldResetN.asBool, "reset_release_reversed")
      Cover((at(Resume) & !targetRun & io.idle).S, io.coldResetN.asBool, "quiesce_return_reversed")
      Cover(powerLost.S, io.coldResetN.asBool, "domain_power_lost")
      Cover((io.serviceFault & !fault).S, io.coldResetN.asBool, "domain_service_lost")
