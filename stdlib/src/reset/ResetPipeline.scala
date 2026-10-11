// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.reset

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ResetPipelineParameter(stages: Int) extends Parameter:
  require(stages >= 1, s"reset pipeline stages must be positive: $stages")

given upickle.default.ReadWriter[ResetPipelineParameter] = upickle.default.macroRW

class ResetPipelineLayers(parameter: ResetPipelineParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ResetPipelineIO(parameter: ResetPipelineParameter) extends HWBundle(parameter):
  val clock      = Flipped(Clock())
  val resetN     = Flipped(Reset())
  val testEnable = Flipped(Bool())
  val output     = Aligned(Reset())

class ResetPipelineProbe(parameter: ResetPipelineParameter)
    extends DVBundle[ResetPipelineParameter, ResetPipelineLayers](parameter)

@generator
object ResetPipeline
    extends Generator[ResetPipelineParameter, ResetPipelineLayers, ResetPipelineIO, ResetPipelineProbe]:
  override def moduleName(parameter: ResetPipelineParameter): String =
    s"ResetPipeline_stages${parameter.stages}"

  def architecture(parameter: ResetPipelineParameter) =
    val io           = summon[Interface[ResetPipelineIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)
    val stages       = Seq.fill(parameter.stages)(RegInit(false.B))
    stages.head := true.B
    stages.tail
      .zip(stages)
      .foreach: (sink, source) =>
        sink := source
    val output = io.testEnable ? (io.resetN.asBool, stages.last)
    io.output := output.asReset

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      Assert((!io.resetN.asBool).S |=> (!stages.last).S, "pipeline_asserts_on_clock")
      Assert(stages.last.S |=> stages.last.S, io.resetN.asBool, "pipeline_stays_released")
      Assert(true.B.S.##(parameter.stages)(true.B.S) |-> stages.last.S, io.resetN.asBool, "pipeline_release_bound")
      (0 until parameter.stages).foreach: cycle =>
        val heldDeasserted = io.resetN.asBool throughout true.B.S.##(cycle)(true.B.S)
        Assert(((!io.resetN.asBool).S ### heldDeasserted) |-> (!stages.last).S, s"pipeline_not_released_early_$cycle")
      Assert((!io.testEnable | (io.output.asBool === io.resetN.asBool)).I, "pipeline_test_bypass")
      Cover((io.resetN.asBool & !stages.last & !io.testEnable).S, true.B, "pipeline_release_pending")
      Cover((stages.last & !io.testEnable).S, true.B, "pipeline_released")
      Cover(io.testEnable.S, true.B, "pipeline_test_bypass")

      Cover((!io.resetN.asBool).S ### io.resetN.asBool.S.##*(io.output.asBool.S), true.B, "reset_release_sequence")
      Cover(
        io.output.asBool.S.##*((!io.resetN.asBool).S).##*((!io.output.asBool).S),
        true.B,
        "reset_reassertion_sequence"
      )
