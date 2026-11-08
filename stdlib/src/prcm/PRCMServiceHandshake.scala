// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.stdlib.default.{instruction, output, PLADecoder}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class PRCMServiceHandshakeParameter() extends Parameter

given upickle.default.ReadWriter[PRCMServiceHandshakeParameter] = upickle.default.macroRW

class PRCMServiceHandshakeLayers(parameter: PRCMServiceHandshakeParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"), Layer("Progress"))

class PRCMServiceHandshakeIO(parameter: PRCMServiceHandshakeParameter) extends HWBundle(parameter):
  val clock      = Flipped(Clock())
  val coldResetN = Flipped(Reset())
  val need       = Flipped(Bool())
  val grant      = Flipped(Bool())
  val fault      = Flipped(Bool())
  val released   = Flipped(Bool())
  val request    = Aligned(Bool())
  val held       = Aligned(Bool())

class PRCMServiceHandshakeProbe(parameter: PRCMServiceHandshakeParameter)
    extends DVBundle[PRCMServiceHandshakeParameter, PRCMServiceHandshakeLayers](parameter)

@generator
object PRCMServiceHandshake
    extends Generator[
      PRCMServiceHandshakeParameter,
      PRCMServiceHandshakeLayers,
      PRCMServiceHandshakeIO,
      PRCMServiceHandshakeProbe
    ]:
  override def moduleName(parameter: PRCMServiceHandshakeParameter): String = "PRCMServiceHandshake"

  def architecture(parameter: PRCMServiceHandshakeParameter) =
    val io           = summon[Interface[PRCMServiceHandshakeIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.asyncActiveLow(io.coldResetN)
    import PRCMServicePhase.*
    val phase        = RegInit(BigInt(Idle.ordinal).B(2))
    val idle         = phase === BigInt(Idle.ordinal).B(2)
    val waiting      = phase === BigInt(Wait.ordinal).B(2)
    val holding      = phase === BigInt(Hold.ordinal).B(2)
    val returning    = phase === BigInt(Return.ordinal).B(2)
    val decoder      = PLADecoder.instantiate(PRCMSequenceDecoder.serviceNext)
    decoder.io.instruction := phase ## io.released.asBits ## io.fault.asBits ## io.grant.asBits ## io.need.asBits
    phase                  := decoder.io.output.field[Bits]("value")
    val outputs = PLADecoder.instantiate(PRCMSequenceDecoder.serviceControl)
    outputs.io.instruction := phase
    val controls = outputs.io.output.field[Bits]("value")
    val request  = controls(0)
    io.request := request
    io.held    := controls(1)

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      val enabled      = io.coldResetN.asBool
      Assert((io.request === (waiting | holding)).S, enabled, "request_covers_wait_and_hold")
      Assert((io.held === holding).S, enabled, "held_means_grant_accepted")
      Assert((idle & io.need).S |=> (waiting & request).S, enabled, "need_starts_request")
      Assert((idle & !io.need).S |=> idle.S, enabled, "idle_without_need")
      Assert((waiting & !io.fault & io.grant).S |=> (holding & request).S, enabled, "grant_accepts_service")
      Assert((waiting & io.fault).S |=> (returning & !request).S, enabled, "failed_request_returns")
      Assert((holding & io.need & !io.fault).S |=> (holding & request).S, enabled, "needed_service_is_retained")
      Assert(
        (holding & (!io.need | io.fault) & io.released).S |=> (returning & !request).S,
        enabled,
        "released_service_returns"
      )
      Assert((returning & !io.grant).S |=> idle.S, enabled, "return_completes_after_grant_low")
      Assert((waiting & !io.fault & !io.grant).S |=> request.S, enabled, "pending_request_is_held")
      Assert((holding & !io.released).S |=> holding.S, enabled, "service_is_held_until_released")
      Assert((returning & io.grant).S |=> (!request).S, enabled, "return_waits_for_grant_low")
      Cover((waiting & !io.need & !io.grant).S, enabled, "pending_need_withdrawn")
      Cover((holding & !io.need & !io.released).S, enabled, "consumer_draining")
      Cover((holding & io.fault).S, enabled, "held_service_failed")
      Cover((returning & !io.grant).S, enabled, "service_returned")

      Cover(idle.S ### waiting.S.##*(holding.S).##*(returning.S).##*(idle.S), enabled, "service_full_lifecycle")
      Cover((waiting & !io.need).S.##*(holding.S).##*(returning.S).##*(idle.S), enabled, "withdrawn_request_drains")
      Cover(
        (holding & io.fault & !io.released).S.##*((returning & !request).S).##*(idle.S),
        enabled,
        "faulted_service_drains"
      )
      Cover(returning.S.##*(idle.S) ### waiting.S.##*(holding.S), enabled, "service_reacquired")

    layer("Progress"):
      given ClockEvent = posedge(io.clock)
      val enabled      = io.coldResetN.asBool
      // Grant and release are external protocol responses, not assumptions on our state.
      val responds     = always(eventually((io.grant | io.fault).S))
      Assert(
        (waiting.S & responds) implies eventually((holding | returning).S),
        enabled,
        "pending_service_eventually_resolves"
      )
      Assert(
        (holding.S & always((!io.need | io.fault).S) & always(eventually(io.released.S))) implies
          eventually(returning.S),
        enabled,
        "released_service_eventually_returns"
      )
      Assert(
        (returning.S & always(eventually((!io.grant).S))) implies eventually(idle.S),
        enabled,
        "grant_withdrawal_completes_return"
      )
