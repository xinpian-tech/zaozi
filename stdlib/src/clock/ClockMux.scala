// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.stdlib.reset.{ResetCounter, ResetCounterParameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockMuxParameter(
  inputs:           Int,
  stages:           Int,
  clockDuringReset: Boolean,
  gate:             ClockGateCellParameter = ClockGateCellParameter(true),
  mux:              ClockMuxCellParameter = ClockMuxCellParameter(),
  or:               ClockOrCellParameter = ClockOrCellParameter())
    extends Parameter:
  require(gate.positive, "ClockMux requires a positive-phase gate")
  require(inputs > 0, "clock mux requires at least one input")
  val selectWidth = BigInt(inputs - 1).bitLength.max(1)
  require(stages >= 1, s"clock mux synchronization stages must be positive: $stages")

given upickle.default.ReadWriter[ClockMuxParameter] = upickle.default.macroRW

class ClockMuxLayers(parameter: ClockMuxParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"), Layer("Progress"))

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
    val io            = summon[Interface[ClockMuxIO]]
    val disabled      = Seq.fill(parameter.inputs)(Wire(Bool()))
    val sourceEnabled = Seq.fill(parameter.inputs)(Wire(Bool()))
    val sourceResetN  = Seq.fill(parameter.inputs)(Wire(Bool()))
    val gated         = (0 until parameter.inputs).map: index =>
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
      off                  := !occupied
      disabled(index)      := off
      sourceEnabled(index) := enabled
      sourceResetN(index)  := receiver.io.output.asBool
      val gate = ClockGate.instantiate(ClockGateParameter(parameter.gate, parameter.clockDuringReset, parameter.mux))
      gate.io.clock      := io.clocks(index)
      gate.io.resetN     := receiver.io.output
      gate.io.enable     := enabled
      gate.io.testEnable := false.B

      layer("Verification"):
        given ClockEvent = posedge(io.clocks(index))
        val active       = receiver.io.output.asBool & !io.testEnable
        Assert((unfiltered.S |=> filter.head.S), receiver.io.output.asBool, s"source_${index}_selection_enters_filter")
        Assert(filter.head.S |=> filter.last.S, receiver.io.output.asBool, s"source_${index}_filter_propagates_one")
        Assert(
          (!filter.head).S |=> (!filter.last).S,
          receiver.io.output.asBool,
          s"source_${index}_filter_propagates_zero"
        )
        Assert(filtered.S |=> stages.head.S, receiver.io.output.asBool, s"source_${index}_enable_enters_pipeline")
        Assert(
          (!filtered).S |=> (!stages.head).S,
          receiver.io.output.asBool,
          s"source_${index}_disable_enters_pipeline"
        )
        stages.indices
          .drop(1)
          .foreach: stage =>
            Assert(
              stages(stage - 1).S |=> stages(stage).S,
              receiver.io.output.asBool,
              s"source_${index}_stage_${stage}_propagates_one"
            )
            Assert(
              (!stages(stage - 1)).S |=> (!stages(stage)).S,
              receiver.io.output.asBool,
              s"source_${index}_stage_${stage}_propagates_zero"
            )
        Assert(occupied.S |=> (!off).S, receiver.io.output.asBool, s"source_${index}_reserved_until_drained")
        Assert((!occupied).S |=> off.S, receiver.io.output.asBool, s"source_${index}_drained_releases_reservation")
        Assert(
          (!unfiltered).S |=> (!filter.head).S,
          receiver.io.output.asBool,
          s"source_${index}_withdrawal_enters_filter"
        )
        Cover((selected & !enabled).S.##*((selected & enabled).S), active, s"source_${index}_selection_completes")
        Cover((!selected & !off).S.##*(off.S), active, s"source_${index}_deselection_drains")
        Cover(
          (selected & enabled).S.##*((!selected & off).S).##*((selected & enabled).S),
          active,
          s"source_${index}_reselected"
        )
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
      val cell = ClockOrCell.instantiate(parameter.or)
      cell.io.a := a
      cell.io.b := b
      cell.io.outClock
    val output = ClockMuxCell.instantiate(parameter.mux)
    output.io.a      := merged
    output.io.b      := io.testClock
    output.io.select := io.testEnable
    io.output        := output.io.outClock

    layer("Verification"):
      given ClockEvent = posedge(io.testClock)
      Cover(io.testEnable.S, true.B, "mux_test_bypass")

      Assert((!io.testEnable | (io.output.asBool === io.testClock.asBool)).I, "mux_test_clock_selected")
      Assert((io.testEnable | (io.output.asBool === merged.asBool)).I, "mux_normal_clock_selected")
      sourceEnabled.indices.foreach: first =>
        (first + 1 until parameter.inputs).foreach: second =>
          val initialized = sourceResetN.foldLeft(true.B: Referable[Bool])(_ & _)
          Assert(
            (!sourceEnabled(first) | !sourceEnabled(second)).I,
            initialized & !io.testEnable,
            s"sources_${first}_${second}_mutually_exclusive"
          )

    layer("Progress"):
      // Each source must keep ticking while its disable pipeline drains. The source
      // edges are explicit so an inactive or stopped source is not silently assumed.
      given ClockEvent = posedge(io.clocks(0))
      val running      = (0 until parameter.inputs).map: index =>
        always(eventually(posedge(io.clocks(index)) { true.B.S }))
      val allRunning   = running.reduce(_ & _)
      sourceEnabled.indices.foreach: index =>
        val selected = io.select === BigInt(index).B(parameter.selectWidth)
        Assert(
          (always((selected & !io.testEnable).S) & allRunning) implies eventually(sourceEnabled(index).S),
          io.resetN.asBool,
          s"source_${index}_eventually_selected"
        )
        Assert(
          (always((selected & !io.testEnable).S) & allRunning) implies
            always(eventually(posedge(io.output) { true.B.S })),
          io.resetN.asBool,
          s"source_${index}_selected_output_keeps_ticking"
        )
        Assert(
          (always((!selected & !io.testEnable).S) & allRunning) implies eventually(disabled(index).S),
          io.resetN.asBool,
          s"source_${index}_eventually_drained"
        )
