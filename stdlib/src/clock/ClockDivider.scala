// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

given mainargs.TokensReader.Simple[BigInt]:
  def shortName = "integer"
  def read(strs: Seq[String]): Right[Nothing, BigInt] = Right(BigInt(strs.head))

case class ClockDividerParameter(width: Int, initial: BigInt, clockDuringReset: Boolean, automatic: Boolean)
    extends Parameter:
  require(width > 0, "divider width must be positive")
  require(initial >= 0 && initial.bitLength <= width, "initial divisor must fit the divider width")
  val initialDivisor = initial.max(1)

given upickle.default.ReadWriter[ClockDividerParameter] = upickle.default.macroRW

class ClockDividerLayers(parameter: ClockDividerParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ClockDividerIO(parameter: ClockDividerParameter) extends HWBundle(parameter):
  val clock      = Flipped(Clock())
  val resetN     = Flipped(Reset())
  // A divided clock can stop high when disabled.
  val enable     = Flipped(Bool())
  val testEnable = Flipped(Bool())
  // Configuration changes belong to the input clock domain.
  val divisor    = Flipped(Bits(parameter.width))
  val valid      = Option.when(!parameter.automatic)(Flipped(Bool()))
  val ready      = Option.when(!parameter.automatic)(Aligned(Bool()))
  val output     = Aligned(Clock())
  val count      = Aligned(UInt(parameter.width))

class ClockDividerProbe(parameter: ClockDividerParameter)
    extends DVBundle[ClockDividerParameter, ClockDividerLayers](parameter)

@generator
object ClockDivider extends Generator[ClockDividerParameter, ClockDividerLayers, ClockDividerIO, ClockDividerProbe]:
  def architecture(parameter: ClockDividerParameter) =
    val io                 = summon[Interface[ClockDividerIO]]
    given ClockScope       = ClockScope.posedge(io.clock)
    given ResetScope       = ResetScope.asyncActiveLow(io.resetN)
    val ready              = Wire(Bool())
    val (requested, valid) = if parameter.automatic then
      val sampled = Seq.fill(2)(RegInit(parameter.initialDivisor.B(parameter.width)))
      sampled.head := io.divisor
      sampled.last := sampled.head
      val latest  = (sampled.last === BigInt(0).B(parameter.width)) ? (BigInt(1).B(parameter.width), sampled.last)
      val request = RegInit(parameter.initialDivisor.B(parameter.width))
      val pending = RegInit(false.B)
      when(pending) {
        when(ready) { pending := false.B }
      }.otherwise {
        when(latest =/= request) {
          request := latest
          pending := true.B
        }
      }
      layer("Verification"):
        given ClockEvent = posedge(io.clock)
        Assert((pending & !ready).S |=> pending.S, io.resetN.asBool, "divider_request_held")
        Cover((pending & !ready & (latest =/= request)).S, io.resetN.asBool, "divider_update_while_busy")
        Cover((pending & ready).S, io.resetN.asBool, "divider_update_accepted")
      (request, pending)
    else
      io.ready.foreach(_ := ready)
      (io.divisor, io.valid.get)
    val divisor            = RegInit(parameter.initialDivisor.U(parameter.width))
    val bypass             = RegInit((parameter.initial < 2).B)
    val odd                = RegInit(parameter.initial.testBit(0).B)
    val state              = RegInit(BigInt(0).B(2))
    val gateEnable         = RegInit(parameter.clockDuringReset.B)
    val count              = RegInit(BigInt(0).U(parameter.width))
    val gate               = ClockGate.instantiate(ClockGateParameter(true, parameter.clockDuringReset))
    gate.io.resetN     := io.resetN
    gate.io.testEnable := io.testEnable
    val gateOpen = ClockScope.posedge(gate.io.clock) { RegInit(parameter.clockDuringReset.B) }
    gateOpen       := gateEnable & io.enable
    gate.io.enable := gateOpen

    val normalized    = (requested === BigInt(0).B(parameter.width)) ? (
      BigInt(1).U(parameter.width),
      requested.asUInt
    )
    val countEnabled  = Wire(Bool())
    val toggleEnabled = Wire(Bool())
    val clear         = Wire(Bool())
    val terminal      = count === (divisor - BigInt(1).U(parameter.width))
    countEnabled  := true.B
    toggleEnabled := true.B
    clear         := false.B
    gateEnable    := false.B
    ready         := false.B
    when(state === BigInt(0).B(2)) {
      gateEnable := true.B
      when(valid) {
        when(normalized === divisor) { ready := true.B }.otherwise {
          state      := BigInt(1).B(2)
          gateEnable := false.B
        }
      }.otherwise {
        when(!io.enable & !gateOpen) {
          countEnabled  := false.B
          toggleEnabled := false.B
        }
      }
    }
    when(state === BigInt(1).B(2)) {
      when(!gateOpen | bypass) {
        toggleEnabled := false.B
        divisor       := normalized
        ready         := true.B
        clear         := true.B
        odd           := normalized.asBits.bit(0)
        bypass        := normalized === BigInt(1).U(parameter.width)
        state         := BigInt(2).B(2)
      }
    }
    when(state === BigInt(2).B(2)) {
      toggleEnabled := false.B
      when(terminal) { state := BigInt(0).B(2) }
    }
    when(clear) { count := BigInt(0).U(parameter.width) }.otherwise {
      when(countEnabled) {
        when(bypass | terminal) { count := BigInt(0).U(parameter.width) }.otherwise {
          count := (count + BigInt(1).U(parameter.width)).asBits.tail(1).asUInt
        }
      }
    }
    val positive = RegInit(false.B)
    val negative       = ClockScope.negedge(io.clock) { RegInit(false.B) }
    val oddHalf        = (divisor + BigInt(1).U(parameter.width)) >> 1
    val positiveToggle = !bypass & toggleEnabled & (
      (count === BigInt(0).U(parameter.width)) | (!odd & (count === (divisor >> 1)))
    )
    val negativeToggle = !bypass & toggleEnabled & odd & (count === oddHalf)
    when(positiveToggle) { positive := !positive }
    when(negativeToggle) { negative := !negative }
    val combined       = ClockCell.instantiate(ClockCellParameter(ClockCellKind.Xor))
    combined.io.a     := positive.asClock
    combined.io.b.get := negative.asClock
    val generated = ClockCell.instantiate(ClockCellParameter(ClockCellKind.Mux))
    generated.io.a          := positive.asClock
    generated.io.b.get      := combined.io.outClock
    generated.io.select.get := odd
    val selected = ClockCell.instantiate(ClockCellParameter(ClockCellKind.Mux))
    selected.io.a          := generated.io.outClock
    selected.io.b.get      := io.clock
    selected.io.select.get := bypass | io.testEnable
    gate.io.clock          := selected.io.outClock
    io.count               := count
    io.output              := gate.io.output

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      Assert((count < divisor).I, io.resetN.asBool, "divider_count_in_period")
      Cover((valid & ready & (normalized === BigInt(1).U(parameter.width))).S, io.resetN.asBool, "accept_bypass")
      if parameter.width > 1 then
        Cover(
          (valid & ready & normalized.asBits.bit(0) & (normalized > BigInt(1).U(parameter.width))).S,
          io.resetN.asBool,
          "accept_odd_divisor"
        )
        Cover((valid & ready & !normalized.asBits.bit(0)).S, io.resetN.asBool, "accept_even_divisor")
        Cover((valid & !ready).S, io.resetN.asBool, "divisor_pending")
      Cover((!io.enable & !gateOpen).S, io.resetN.asBool, "divider_disabled")
