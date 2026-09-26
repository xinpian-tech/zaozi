// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.stdlib.reset.{ResetCounter, ResetCounterParameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockMuxParameter(inputs: Int, stages: Int, clockDuringReset: Boolean) extends Parameter:
  require(inputs > 0, "clock mux requires at least one input")
  val selectWidth = BigInt(inputs - 1).bitLength.max(1)
  require(stages >= 1, s"clock mux synchronization stages must be positive: $stages")

given upickle.default.ReadWriter[ClockMuxParameter] = upickle.default.macroRW

class ClockMuxLayers(parameter: ClockMuxParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ClockMuxIO(parameter: ClockMuxParameter) extends HWBundle(parameter):
  val clocks     = Flipped(Vec(parameter.inputs, Clock()))
  val resetN     = Flipped(Reset())
  val select     = Flipped(Bits(parameter.selectWidth))
  val testClock  = Flipped(Clock())
  val testEnable = Flipped(Bool())
  val output     = Aligned(Clock())

class ClockMuxProbe(parameter: ClockMuxParameter) extends DVBundle[ClockMuxParameter, ClockMuxLayers](parameter)

@generator
object ClockMux extends Generator[ClockMuxParameter, ClockMuxLayers, ClockMuxIO, ClockMuxProbe]:
  def architecture(parameter: ClockMuxParameter) =
    val io       = summon[Interface[ClockMuxIO]]
    val disabled = Seq.fill(parameter.inputs)(Wire(Bool()))
    val gated    = (0 until parameter.inputs).map: index =>
      val receiver = ResetCounter.instantiate(ResetCounterParameter(1))
      receiver.io.clock      := io.clocks(index)
      receiver.io.resetN     := io.resetN
      receiver.io.testEnable := false.B
      given ClockScope = ClockScope.posedge(io.clocks(index))
      given ResetScope = ResetScope.asyncActiveLow(receiver.io.output)
      val selected     = io.select === BigInt(index).B(parameter.selectWidth)
      val othersOff    = disabled.zipWithIndex
        .filter(_._2 != index)
        .map(_._1: Referable[Bool])
        .foldLeft(true.B: Referable[Bool])(_ & _)
      val unfiltered   = selected & othersOff
      val filter       = Seq.fill(2)(RegInit(false.B))
      filter.head := unfiltered
      filter.last := filter.head
      val filtered = filter.head & filter.last & unfiltered
      val stages   = Seq.fill(parameter.stages)(RegInit(false.B))
      stages.head := filtered
      stages.tail
        .zip(stages)
        .foreach: (sink, source) =>
          sink := source
      val enabled = if parameter.clockDuringReset then
        val bypass = RegInit(true.B)
        bypass := false.B
        bypass ? (unfiltered, stages.last)
      else stages.last
      // A queued enable still owns its source until the entire pipeline has drained.
      val occupied = (filter ++ stages).foldLeft((unfiltered | enabled): Referable[Bool])(_ | _)
      val off = RegInit(true.B)
      off             := !occupied
      disabled(index) := off
      val gate = ClockGate.instantiate(ClockGateParameter(true, parameter.clockDuringReset))
      gate.io.clock      := io.clocks(index)
      gate.io.resetN     := receiver.io.output
      gate.io.enable     := enabled
      gate.io.testEnable := false.B

      layer("Verification"):
        given ClockEvent = posedge(io.clocks(index))
        Cover((selected & enabled).S, receiver.io.output.asBool & !io.testEnable, s"source_${index}_enabled")
        Cover((!off & !enabled).S, receiver.io.output.asBool & !io.testEnable, s"source_${index}_reserved")
        Cover((!selected & enabled).S, receiver.io.output.asBool & !io.testEnable, s"source_${index}_release_pending")
        if BigInt(parameter.inputs) < (BigInt(1) << parameter.selectWidth) then
          Cover(
            (io.select.asUInt >= BigInt(parameter.inputs).U(parameter.selectWidth)).S,
            receiver.io.output.asBool,
            s"source_${index}_invalid_selection"
          )
      gate.io.output

    val merged = gated.reduce: (a, b) =>
      val cell = ClockCell.instantiate(ClockCellParameter(ClockCellKind.Or))
      cell.io.a     := a
      cell.io.b.get := b
      cell.io.outClock
    val output = ClockCell.instantiate(ClockCellParameter(ClockCellKind.Mux))
    output.io.a          := merged
    output.io.b.get      := io.testClock
    output.io.select.get := io.testEnable
    io.output            := output.io.outClock

    layer("Verification"):
      given ClockEvent = posedge(io.testClock)
      Cover(io.testEnable.S, true.B, "mux_test_bypass")
