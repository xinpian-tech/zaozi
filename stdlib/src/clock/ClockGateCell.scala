// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockGateCellParameter(positive: Boolean, technology: Option[ClockGateTechnologyParameter] = None)
    extends Parameter
given upickle.default.ReadWriter[ClockGateCellParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockGateCellParameter]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, ClockGateCellParameter] = Right(
    upickle.default.read[ClockGateCellParameter](strs.head)
  )

/** Fab bindings for a gate family. The requested phase is supplied when creating a cell. */
case class ClockGateTechnologyMapping(
  positive: Option[ClockGateTechnologyParameter] = None,
  negative: Option[ClockGateTechnologyParameter] = None):
  def cell(positivePhase: Boolean): ClockGateCellParameter =
    ClockGateCellParameter(positivePhase, if positivePhase then positive else negative)

given upickle.default.ReadWriter[ClockGateTechnologyMapping] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockGateTechnologyMapping]:
  def shortName = "gate-technology"
  def read(strs: Seq[String]): Right[Nothing, ClockGateTechnologyMapping] = Right(
    upickle.default.read[ClockGateTechnologyMapping](strs.head)
  )

// Both behavior and technology architectures implement this fixed interface.
class ClockGateCellIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Clock())
  val enable   = Flipped(Bool())
  val outClock = Aligned(Clock())

// Keep clocks typed at the public boundary. The choice itself switches plain wires,
// which CIRCT can preserve as Verilog conditional instances.
private[clock] class ClockGateChoiceIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Bool())
  val enable   = Flipped(Bool())
  val outClock = Aligned(Bool())

class ClockGateChoiceLayers(parameter: Parameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ClockGateChoiceProbe(parameter: Parameter) extends DVBundle[Parameter, ClockGateChoiceLayers](parameter)

class ClockGateCellLayers(parameter: ClockGateCellParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ClockGateCellProbe(parameter: ClockGateCellParameter)
    extends DVBundle[ClockGateCellParameter, ClockGateCellLayers](parameter)

case class ClockGateCellVerilogParameter() extends VerilogParameter

@generator
object ClockGateCell
    extends Generator[ClockGateCellParameter, ClockGateCellLayers, ClockGateCellIO, ClockGateCellProbe]:
  def architecture(parameter: ClockGateCellParameter) =
    val io       = summon[Interface[ClockGateCellIO]]
    val instance = parameter.technology match
      case Some(binding) =>
        InstanceChoice[ClockGateChoiceLayers, ClockGateChoiceIO, ClockGateChoiceProbe](
          Seq(
            "Behavior"   -> ClockGateBehaviorArchitecture.architecture(parameter.copy(technology = None)),
            "Technology" -> ClockGateTechnology.architecture(binding)
          )
        )
      case None          =>
        InstanceChoice[ClockGateChoiceLayers, ClockGateChoiceIO, ClockGateChoiceProbe](
          Seq(
            "Behavior" -> ClockGateBehaviorArchitecture.architecture(parameter)
          )
        )
    instance.io.a := io.a.asBool
    instance.io.enable := io.enable
    io.outClock        := instance.io.outClock.asClock

    layer("Verification"):
      val active       = if parameter.positive then io.a.asBool else !io.a.asBool
      val outputActive = if parameter.positive then io.outClock.asBool else !io.outClock.asBool
      Assert((active | !outputActive).I, "gate_inactive_phase")
      // Sample the enable at the opening edge and the pulse at the closing edge.
      // The enable may change during the active phase without truncating the pulse.
      val opening      = if parameter.positive then posedge(io.a) else negedge(io.a)
      val closing      = if parameter.positive then negedge(io.a) else posedge(io.a)
      given ClockEvent = closing
      Assert(opening { io.enable.S } |=> closing { outputActive.S }, "gate_enabled_pulse")
      Assert(opening { (!io.enable).S } |=> closing { (!outputActive).S }, "gate_disabled_pulse")

@generator
private[clock] object ClockGateBehaviorArchitecture
    extends Generator[ClockGateCellParameter, ClockGateChoiceLayers, ClockGateChoiceIO, ClockGateChoiceProbe]:
  def architecture(parameter: ClockGateCellParameter) =
    val io       = summon[Interface[ClockGateChoiceIO]]
    val instance = ClockGateBehavior.instantiate(parameter)
    instance.io.a      := io.a
    instance.io.enable := io.enable
    io.outClock        := instance.io.outClock

@generator
private[clock] object ClockGateBehavior
    extends VerilogWrapper[
      ClockGateCellParameter,
      ClockGateChoiceLayers,
      ClockGateChoiceIO,
      ClockGateChoiceProbe,
      ClockGateCellVerilogParameter
    ]:
  override def moduleName(parameter: ClockGateCellParameter):     String              = verilogModuleName(parameter)
  def verilogModuleName(parameter: ClockGateCellParameter):       String              =
    if parameter.positive then "ClockGatePositiveBehavior" else "ClockGateNegativeBehavior"
  def verilogParameter(parameter:    ClockGateCellParameter) = ClockGateCellVerilogParameter()
  override def verilogSources(parameter: ClockGateCellParameter): Map[String, String] =
    val name   = verilogModuleName(parameter)
    val latch  = if parameter.positive then "if (!a) held = enable" else "if (a) held = !enable"
    val output = if parameter.positive then "a & held" else "a | held"
    Map(s"$name.sv" -> s"""module $name(input wire a, input wire enable, output wire outClock);
                          |  reg held;
                          |  always @* $latch;
                          |  assign outClock = $output;
                          |endmodule
                          |""".stripMargin)

case class ClockGateTechnologyParameter(
  moduleName: String,
  a:          String = "a",
  enable:     String = "enable",
  outClock:   String = "outClock",
  sources:    Map[String, String] = Map.empty)
    extends Parameter:
  require(moduleName.nonEmpty, "technology module name must not be empty")
  private val pins = Seq(a, enable, outClock)
  require(pins.forall(_.nonEmpty) && pins.distinct.size == pins.size, "technology pins must be nonempty and distinct")

given upickle.default.ReadWriter[ClockGateTechnologyParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockGateTechnologyParameter]:
  def shortName = "gate-cell"
  def read(strs: Seq[String]): Right[Nothing, ClockGateTechnologyParameter] = Right(
    upickle.default.read[ClockGateTechnologyParameter](strs.head)
  )

private[clock] class ClockGateTechnologyIO(parameter: ClockGateTechnologyParameter) extends HWRecord(parameter):
  Flipped(parameter.a, Bool())
  Flipped(parameter.enable, Bool())
  Aligned(parameter.outClock, Bool())

@generator
private[clock] object ClockGateTechnology
    extends Generator[ClockGateTechnologyParameter, ClockGateChoiceLayers, ClockGateChoiceIO, ClockGateChoiceProbe]:
  def architecture(parameter: ClockGateTechnologyParameter) =
    val io       = summon[Interface[ClockGateChoiceIO]]
    val instance = ClockGateTechnologyWrapper.instantiate(parameter)
    instance.io.field[Bool](parameter.a)      := io.a
    instance.io.field[Bool](parameter.enable) := io.enable
    io.outClock                               := instance.io.field[Bool](parameter.outClock)

@generator
private[clock] object ClockGateTechnologyWrapper
    extends VerilogWrapper[
      ClockGateTechnologyParameter,
      ClockGateChoiceLayers,
      ClockGateTechnologyIO,
      ClockGateChoiceProbe,
      ClockGateCellVerilogParameter
    ]:
  override def moduleName(parameter:     ClockGateTechnologyParameter): String              = parameter.moduleName
  def verilogModuleName(parameter:       ClockGateTechnologyParameter): String              = parameter.moduleName
  def verilogParameter(parameter:        ClockGateTechnologyParameter) = ClockGateCellVerilogParameter()
  override def verilogSources(parameter: ClockGateTechnologyParameter): Map[String, String] = parameter.sources
