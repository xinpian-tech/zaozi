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
  def layers = Seq(Layer("Verification"))

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
    val phase        = RegInit(BigInt(0).B(2))
    val waiting      = phase === BigInt(1).B(2)
    val holding      = phase === BigInt(2).B(2)
    val returning    = phase === BigInt(3).B(2)
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
      Assert((waiting & !io.fault & !io.grant).S |=> request.S, enabled, "pending_request_is_held")
      Assert((holding & !io.released).S |=> holding.S, enabled, "service_is_held_until_released")
      Assert((returning & io.grant).S |=> (!request).S, enabled, "return_waits_for_grant_low")
      Cover((waiting & !io.need & !io.grant).S, enabled, "pending_need_withdrawn")
      Cover((holding & !io.need & !io.released).S, enabled, "consumer_draining")
      Cover((holding & io.fault).S, enabled, "held_service_failed")
      Cover((returning & !io.grant).S, enabled, "service_returned")
