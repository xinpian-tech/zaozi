// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockOrCellParameter(technology: Option[ClockOrTechnologyParameter] = None) extends Parameter
given upickle.default.ReadWriter[ClockOrCellParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockOrCellParameter]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, ClockOrCellParameter] = Right(
    upickle.default.read[ClockOrCellParameter](strs.head)
  )

// Both behavior and technology architectures implement this fixed interface.
class ClockOrCellIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Clock())
  val b        = Flipped(Clock())
  val outClock = Aligned(Clock())

// Keep clocks typed at the public boundary. The choice itself switches plain wires,
// which CIRCT can preserve as Verilog conditional instances.
private[clock] class ClockOrChoiceIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Bool())
  val b        = Flipped(Bool())
  val outClock = Aligned(Bool())

class ClockOrChoiceLayers(parameter: Parameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ClockOrChoiceProbe(parameter: Parameter) extends DVBundle[Parameter, ClockOrChoiceLayers](parameter)

class ClockOrCellLayers(parameter: ClockOrCellParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ClockOrCellProbe(parameter: ClockOrCellParameter)
    extends DVBundle[ClockOrCellParameter, ClockOrCellLayers](parameter)

case class ClockOrCellVerilogParameter() extends VerilogParameter

@generator
object ClockOrCell extends Generator[ClockOrCellParameter, ClockOrCellLayers, ClockOrCellIO, ClockOrCellProbe]:
  def architecture(parameter: ClockOrCellParameter) =
    val io       = summon[Interface[ClockOrCellIO]]
    val instance = parameter.technology match
      case Some(binding) =>
        InstanceChoice[ClockOrChoiceLayers, ClockOrChoiceIO, ClockOrChoiceProbe](
          Seq(
            "Behavior"   -> ClockOrBehaviorArchitecture.architecture(parameter.copy(technology = None)),
            "Technology" -> ClockOrTechnology.architecture(binding)
          )
        )
      case None          =>
        InstanceChoice[ClockOrChoiceLayers, ClockOrChoiceIO, ClockOrChoiceProbe](
          Seq(
            "Behavior" -> ClockOrBehaviorArchitecture.architecture(parameter)
          )
        )
    instance.io.a := io.a.asBool
    instance.io.b := io.b.asBool
    io.outClock   := instance.io.outClock.asClock

    layer("Verification"):
      Assert((io.outClock.asBool === (io.a.asBool | io.b.asBool)).I, "clock_or_function")

@generator
private[clock] object ClockOrBehaviorArchitecture
    extends Generator[ClockOrCellParameter, ClockOrChoiceLayers, ClockOrChoiceIO, ClockOrChoiceProbe]:
  def architecture(parameter: ClockOrCellParameter) =
    val io       = summon[Interface[ClockOrChoiceIO]]
    val instance = ClockOrBehavior.instantiate(parameter)
    instance.io.a := io.a
    instance.io.b := io.b
    io.outClock   := instance.io.outClock

@generator
private[clock] object ClockOrBehavior
    extends VerilogWrapper[
      ClockOrCellParameter,
      ClockOrChoiceLayers,
      ClockOrChoiceIO,
      ClockOrChoiceProbe,
      ClockOrCellVerilogParameter
    ]:
  override def moduleName(parameter: ClockOrCellParameter):     String              = verilogModuleName(parameter)
  def verilogModuleName(parameter:   ClockOrCellParameter):     String              = "ClockOrBehavior"
  def verilogParameter(parameter:    ClockOrCellParameter) = ClockOrCellVerilogParameter()
  override def verilogSources(parameter: ClockOrCellParameter): Map[String, String] =
    val name = verilogModuleName(parameter)
    Map(s"$name.sv" -> s"""module $name(input wire a, input wire b, output wire outClock);
                          |  assign outClock = a | b;
                          |endmodule
                          |""".stripMargin)

case class ClockOrTechnologyParameter(
  moduleName: String,
  a:          String = "a",
  b:          String = "b",
  outClock:   String = "outClock",
  sources:    Map[String, String] = Map.empty)
    extends Parameter:
  require(moduleName.nonEmpty, "technology module name must not be empty")
  private val pins = Seq(a, b, outClock)
  require(pins.forall(_.nonEmpty) && pins.distinct.size == pins.size, "technology pins must be nonempty and distinct")

given upickle.default.ReadWriter[ClockOrTechnologyParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockOrTechnologyParameter]:
  def shortName = "or-cell"
  def read(strs: Seq[String]): Right[Nothing, ClockOrTechnologyParameter] = Right(
    upickle.default.read[ClockOrTechnologyParameter](strs.head)
  )

private[clock] class ClockOrTechnologyIO(parameter: ClockOrTechnologyParameter) extends HWRecord(parameter):
  Flipped(parameter.a, Bool())
  Flipped(parameter.b, Bool())
  Aligned(parameter.outClock, Bool())

@generator
private[clock] object ClockOrTechnology
    extends Generator[ClockOrTechnologyParameter, ClockOrChoiceLayers, ClockOrChoiceIO, ClockOrChoiceProbe]:
  def architecture(parameter: ClockOrTechnologyParameter) =
    val io       = summon[Interface[ClockOrChoiceIO]]
    val instance = ClockOrTechnologyWrapper.instantiate(parameter)
    instance.io.field[Bool](parameter.a) := io.a
    instance.io.field[Bool](parameter.b) := io.b
    io.outClock                          := instance.io.field[Bool](parameter.outClock)

@generator
private[clock] object ClockOrTechnologyWrapper
    extends VerilogWrapper[
      ClockOrTechnologyParameter,
      ClockOrChoiceLayers,
      ClockOrTechnologyIO,
      ClockOrChoiceProbe,
      ClockOrCellVerilogParameter
    ]:
  override def moduleName(parameter:     ClockOrTechnologyParameter): String              = parameter.moduleName
  def verilogModuleName(parameter:       ClockOrTechnologyParameter): String              = parameter.moduleName
  def verilogParameter(parameter:        ClockOrTechnologyParameter) = ClockOrCellVerilogParameter()
  override def verilogSources(parameter: ClockOrTechnologyParameter): Map[String, String] = parameter.sources
