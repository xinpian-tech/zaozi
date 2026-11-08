// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockInverterCellParameter(technology: Option[ClockInverterTechnologyParameter] = None) extends Parameter
given upickle.default.ReadWriter[ClockInverterCellParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockInverterCellParameter]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, ClockInverterCellParameter] = Right(
    upickle.default.read[ClockInverterCellParameter](strs.head)
  )

// Both behavior and technology architectures implement this fixed interface.
class ClockInverterCellIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Clock())
  val outClock = Aligned(Clock())

// Keep clocks typed at the public boundary. The choice itself switches plain wires,
// which CIRCT can preserve as Verilog conditional instances.
private[clock] class ClockInverterChoiceIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Bool())
  val outClock = Aligned(Bool())

class ClockInverterChoiceLayers(parameter: Parameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ClockInverterChoiceProbe(parameter: Parameter) extends DVBundle[Parameter, ClockInverterChoiceLayers](parameter)

class ClockInverterCellLayers(parameter: ClockInverterCellParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ClockInverterCellProbe(parameter: ClockInverterCellParameter)
    extends DVBundle[ClockInverterCellParameter, ClockInverterCellLayers](parameter)

case class ClockInverterCellVerilogParameter() extends VerilogParameter

@generator
object ClockInverterCell
    extends Generator[ClockInverterCellParameter, ClockInverterCellLayers, ClockInverterCellIO, ClockInverterCellProbe]:
  def architecture(parameter: ClockInverterCellParameter) =
    val io       = summon[Interface[ClockInverterCellIO]]
    val instance = parameter.technology match
      case Some(binding) =>
        InstanceChoice[ClockInverterChoiceLayers, ClockInverterChoiceIO, ClockInverterChoiceProbe](
          Seq(
            "Behavior"   -> ClockInverterBehaviorArchitecture.architecture(parameter.copy(technology = None)),
            "Technology" -> ClockInverterTechnology.architecture(binding)
          )
        )
      case None          =>
        InstanceChoice[ClockInverterChoiceLayers, ClockInverterChoiceIO, ClockInverterChoiceProbe](
          Seq(
            "Behavior" -> ClockInverterBehaviorArchitecture.architecture(parameter)
          )
        )
    instance.io.a := io.a.asBool
    io.outClock := instance.io.outClock.asClock

    layer("Verification"):
      Assert((io.outClock.asBool === (!io.a.asBool)).I, "clock_inverter_function")

@generator
private[clock] object ClockInverterBehaviorArchitecture
    extends Generator[
      ClockInverterCellParameter,
      ClockInverterChoiceLayers,
      ClockInverterChoiceIO,
      ClockInverterChoiceProbe
    ]:
  def architecture(parameter: ClockInverterCellParameter) =
    val io       = summon[Interface[ClockInverterChoiceIO]]
    val instance = ClockInverterBehavior.instantiate(parameter)
    instance.io.a := io.a
    io.outClock   := instance.io.outClock

@generator
private[clock] object ClockInverterBehavior
    extends VerilogWrapper[
      ClockInverterCellParameter,
      ClockInverterChoiceLayers,
      ClockInverterChoiceIO,
      ClockInverterChoiceProbe,
      ClockInverterCellVerilogParameter
    ]:
  override def moduleName(parameter: ClockInverterCellParameter):     String              = verilogModuleName(parameter)
  def verilogModuleName(parameter:   ClockInverterCellParameter):     String              = "ClockInverterBehavior"
  def verilogParameter(parameter:    ClockInverterCellParameter) = ClockInverterCellVerilogParameter()
  override def verilogSources(parameter: ClockInverterCellParameter): Map[String, String] =
    val name = verilogModuleName(parameter)
    Map(s"$name.sv" -> s"""module $name(input wire a, output wire outClock);
                          |  assign outClock = ~a;
                          |endmodule
                          |""".stripMargin)

case class ClockInverterTechnologyParameter(
  moduleName: String,
  a:          String = "a",
  outClock:   String = "outClock",
  sources:    Map[String, String] = Map.empty)
    extends Parameter:
  require(moduleName.nonEmpty, "technology module name must not be empty")
  private val pins = Seq(a, outClock)
  require(pins.forall(_.nonEmpty) && pins.distinct.size == pins.size, "technology pins must be nonempty and distinct")

given upickle.default.ReadWriter[ClockInverterTechnologyParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockInverterTechnologyParameter]:
  def shortName = "inverter-cell"
  def read(strs: Seq[String]): Right[Nothing, ClockInverterTechnologyParameter] = Right(
    upickle.default.read[ClockInverterTechnologyParameter](strs.head)
  )

private[clock] class ClockInverterTechnologyIO(parameter: ClockInverterTechnologyParameter) extends HWRecord(parameter):
  Flipped(parameter.a, Bool())
  Aligned(parameter.outClock, Bool())

@generator
private[clock] object ClockInverterTechnology
    extends Generator[
      ClockInverterTechnologyParameter,
      ClockInverterChoiceLayers,
      ClockInverterChoiceIO,
      ClockInverterChoiceProbe
    ]:
  def architecture(parameter: ClockInverterTechnologyParameter) =
    val io       = summon[Interface[ClockInverterChoiceIO]]
    val instance = ClockInverterTechnologyWrapper.instantiate(parameter)
    instance.io.field[Bool](parameter.a) := io.a
    io.outClock                          := instance.io.field[Bool](parameter.outClock)

@generator
private[clock] object ClockInverterTechnologyWrapper
    extends VerilogWrapper[
      ClockInverterTechnologyParameter,
      ClockInverterChoiceLayers,
      ClockInverterTechnologyIO,
      ClockInverterChoiceProbe,
      ClockInverterCellVerilogParameter
    ]:
  override def moduleName(parameter:     ClockInverterTechnologyParameter): String              = parameter.moduleName
  def verilogModuleName(parameter:       ClockInverterTechnologyParameter): String              = parameter.moduleName
  def verilogParameter(parameter:        ClockInverterTechnologyParameter) = ClockInverterCellVerilogParameter()
  override def verilogSources(parameter: ClockInverterTechnologyParameter): Map[String, String] = parameter.sources
