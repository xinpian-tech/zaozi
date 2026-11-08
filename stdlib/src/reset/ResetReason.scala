// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.reset

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ResetReasonParameter(sources: Int) extends Parameter:
  require(sources > 0, "ResetReason requires at least one event source")

given upickle.default.ReadWriter[ResetReasonParameter] = upickle.default.macroRW

class ResetReasonLayers(parameter: ResetReasonParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ResetReasonIO(parameter: ResetReasonParameter) extends HWBundle(parameter):
  val clock      = Flipped(Clock())
  val coldResetN = Flipped(Reset())
  // Events assert high and set their flags without waiting for the clock.
  val events     = Flipped(Vec(parameter.sources, Reset()))
  val clear      = Flipped(Bool())
  val reasons    = Aligned(Bits(parameter.sources))
  val valid      = Aligned(Bool())

class ResetReasonProbe(parameter: ResetReasonParameter)
    extends DVBundle[ResetReasonParameter, ResetReasonLayers](parameter)

@generator
object ResetReason extends Generator[ResetReasonParameter, ResetReasonLayers, ResetReasonIO, ResetReasonProbe]:
  override def moduleName(parameter: ResetReasonParameter): String = s"ResetReason_sources${parameter.sources}"

  def architecture(parameter: ResetReasonParameter) =
    val io           = summon[Interface[ResetReasonIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.asyncActiveLow(io.coldResetN)
    val clearStages  = Seq.fill(3)(RegInit(false.B))
    clearStages.head := io.clear
    clearStages.tail
      .zip(clearStages)
      .foreach: (sink, source) =>
        sink := source
    val clearPulse = clearStages(1) & !clearStages(2)
    val initialized = RegInit(false.B)
    val clearWindow = RegInit(BigInt(0).B(2))
    val valid       = RegInit(false.B)
    val clearing    = clearWindow.orR
    when(!initialized | clearPulse) {
      initialized := true.B
      clearWindow := BigInt(3).B(2)
      valid       := false.B
    }.otherwise {
      when(clearing) {
        clearWindow := false.B.asBits ## clearWindow.bit(1).asBits
      }.otherwise {
        valid := true.B
      }
    }
    val flags       = (0 until parameter.sources).map: index =>
      given ResetScope = ResetScope.asyncActiveHigh(io.events(index))
      val flag         = RegInit(true.B)
      when(clearing) { flag := false.B }
      flag
    val captured    = flags.reverse.map(flag => flag.asBits: Referable[Bits]).reduce(_ ## _)
    val reasons     = valid ? (captured, BigInt(0).B(parameter.sources))
    io.reasons := reasons
    io.valid   := valid

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      Assert(clearPulse.S |=> (!valid).S, io.coldResetN.asBool, "clear_invalidates_reasons")
      Assert(
        (!initialized | clearPulse).S |=> (clearWindow === BigInt(3).B(2)).S,
        io.coldResetN.asBool,
        "reason_clear_opens_capture_window"
      )
      Assert(
        (initialized & !clearPulse & (clearWindow === BigInt(3).B(2))).S |=>
          (clearWindow === BigInt(1).B(2)).S,
        io.coldResetN.asBool,
        "reason_clear_window_advances"
      )
      Assert(
        (initialized & !clearPulse & (clearWindow === BigInt(1).B(2))).S |=>
          (!clearing).S,
        io.coldResetN.asBool,
        "reason_clear_window_closes"
      )
      Assert((valid & !clearPulse).S |=> valid.S, io.coldResetN.asBool, "reason_valid_stays_until_clear")
      Cover((valid & captured.orR).S, io.coldResetN.asBool, "reset_reason_captured")
      val anyEvent     = (0 until parameter.sources).map(index => io.events(index).asBool: Referable[Bool]).reduce(_ | _)
      Cover((clearing & anyEvent).S, io.coldResetN.asBool, "event_during_clear")
      Cover((clearPulse & clearing).S, io.coldResetN.asBool, "clear_retriggered")

      Assert(
        (valid | (io.reasons === BigInt(0).B(parameter.sources))).S,
        io.coldResetN.asBool,
        "invalid_reasons_hidden"
      )
      Assert((!clearing & initialized & !clearPulse).S |=> valid.S, io.coldResetN.asBool, "capture_window_completes")
      (0 until parameter.sources).foreach: index =>
        val event = io.events(index).asBool
        Assert((!event | flags(index)).I, "event_asserts_flag_" + index)
        Assert((flags(index) & !clearing).S |=> flags(index).S, io.coldResetN.asBool, s"reason_${index}_sticky")
        Assert((clearing & !event).S |=> ((!flags(index)) | event).S, io.coldResetN.asBool, s"reason_${index}_cleared")
        Cover((event & valid).S.##*(io.reasons.bit(index).S), io.coldResetN.asBool, s"reason_${index}_captured")
        Cover(
          (io.reasons.bit(index) & clearPulse).S.##*((valid & !io.reasons.bit(index)).S),
          io.coldResetN.asBool,
          s"reason_${index}_clear_complete"
        )
      Cover((!io.coldResetN.asBool).S.##*((valid & io.coldResetN.asBool).S), true.B, "reason_cold_start_complete")
