// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockBufferCellParameter(technology: Option[ClockBufferTechnologyParameter] = None) extends Parameter
given upickle.default.ReadWriter[ClockBufferCellParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockBufferCellParameter]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, ClockBufferCellParameter] = Right(
    upickle.default.read[ClockBufferCellParameter](strs.head)
  )

// Both behavior and technology architectures implement this fixed interface.
class ClockBufferCellIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Clock())
  val outClock = Aligned(Clock())

// Keep clocks typed at the public boundary. The choice itself switches plain wires,
// which CIRCT can preserve as Verilog conditional instances.
private[clock] class ClockBufferChoiceIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Bool())
  val outClock = Aligned(Bool())

class ClockBufferChoiceLayers(parameter: Parameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ClockBufferChoiceProbe(parameter: Parameter) extends DVBundle[Parameter, ClockBufferChoiceLayers](parameter)

class ClockBufferCellLayers(parameter: ClockBufferCellParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ClockBufferCellProbe(parameter: ClockBufferCellParameter)
    extends DVBundle[ClockBufferCellParameter, ClockBufferCellLayers](parameter)

case class ClockBufferCellVerilogParameter() extends VerilogParameter

@generator
object ClockBufferCell
    extends Generator[ClockBufferCellParameter, ClockBufferCellLayers, ClockBufferCellIO, ClockBufferCellProbe]:
  def architecture(parameter: ClockBufferCellParameter) =
    val io       = summon[Interface[ClockBufferCellIO]]
    val instance = parameter.technology match
      case Some(binding) =>
        InstanceChoice[ClockBufferChoiceLayers, ClockBufferChoiceIO, ClockBufferChoiceProbe](
          Seq(
            "Behavior"   -> ClockBufferBehaviorArchitecture.architecture(parameter.copy(technology = None)),
            "Technology" -> ClockBufferTechnology.architecture(binding)
          )
        )
      case None          =>
        InstanceChoice[ClockBufferChoiceLayers, ClockBufferChoiceIO, ClockBufferChoiceProbe](
          Seq(
            "Behavior" -> ClockBufferBehaviorArchitecture.architecture(parameter)
          )
        )
    instance.io.a := io.a.asBool
    io.outClock := instance.io.outClock.asClock

    layer("Verification"):
      Assert((io.outClock.asBool === (io.a.asBool)).I, "clock_buffer_function")

@generator
private[clock] object ClockBufferBehaviorArchitecture
    extends Generator[ClockBufferCellParameter, ClockBufferChoiceLayers, ClockBufferChoiceIO, ClockBufferChoiceProbe]:
  def architecture(parameter: ClockBufferCellParameter) =
    val io       = summon[Interface[ClockBufferChoiceIO]]
    val instance = ClockBufferBehavior.instantiate(parameter)
    instance.io.a := io.a
    io.outClock   := instance.io.outClock

@generator
private[clock] object ClockBufferBehavior
    extends VerilogWrapper[
      ClockBufferCellParameter,
      ClockBufferChoiceLayers,
      ClockBufferChoiceIO,
      ClockBufferChoiceProbe,
      ClockBufferCellVerilogParameter
    ]:
  override def moduleName(parameter: ClockBufferCellParameter):     String              = verilogModuleName(parameter)
  def verilogModuleName(parameter:   ClockBufferCellParameter):     String              = "ClockBufferBehavior"
  def verilogParameter(parameter:    ClockBufferCellParameter) = ClockBufferCellVerilogParameter()
  override def verilogSources(parameter: ClockBufferCellParameter): Map[String, String] =
    val name = verilogModuleName(parameter)
    Map(s"$name.sv" -> s"""module $name(input wire a, output wire outClock);
                          |  assign outClock = a;
                          |endmodule
                          |""".stripMargin)

case class ClockBufferTechnologyParameter(
  moduleName: String,
  a:          String = "a",
  outClock:   String = "outClock",
  sources:    Map[String, String] = Map.empty)
    extends Parameter:
  require(moduleName.nonEmpty, "technology module name must not be empty")
  private val pins = Seq(a, outClock)
  require(pins.forall(_.nonEmpty) && pins.distinct.size == pins.size, "technology pins must be nonempty and distinct")

given upickle.default.ReadWriter[ClockBufferTechnologyParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockBufferTechnologyParameter]:
  def shortName = "buffer-cell"
  def read(strs: Seq[String]): Right[Nothing, ClockBufferTechnologyParameter] = Right(
    upickle.default.read[ClockBufferTechnologyParameter](strs.head)
  )

private[clock] class ClockBufferTechnologyIO(parameter: ClockBufferTechnologyParameter) extends HWRecord(parameter):
  Flipped(parameter.a, Bool())
  Aligned(parameter.outClock, Bool())

@generator
private[clock] object ClockBufferTechnology
    extends Generator[
      ClockBufferTechnologyParameter,
      ClockBufferChoiceLayers,
      ClockBufferChoiceIO,
      ClockBufferChoiceProbe
    ]:
  def architecture(parameter: ClockBufferTechnologyParameter) =
    val io       = summon[Interface[ClockBufferChoiceIO]]
    val instance = ClockBufferTechnologyWrapper.instantiate(parameter)
    instance.io.field[Bool](parameter.a) := io.a
    io.outClock                          := instance.io.field[Bool](parameter.outClock)

@generator
private[clock] object ClockBufferTechnologyWrapper
    extends VerilogWrapper[
      ClockBufferTechnologyParameter,
      ClockBufferChoiceLayers,
      ClockBufferTechnologyIO,
      ClockBufferChoiceProbe,
      ClockBufferCellVerilogParameter
    ]:
  override def moduleName(parameter:     ClockBufferTechnologyParameter): String              = parameter.moduleName
  def verilogModuleName(parameter:       ClockBufferTechnologyParameter): String              = parameter.moduleName
  def verilogParameter(parameter:        ClockBufferTechnologyParameter) = ClockBufferCellVerilogParameter()
  override def verilogSources(parameter: ClockBufferTechnologyParameter): Map[String, String] = parameter.sources
