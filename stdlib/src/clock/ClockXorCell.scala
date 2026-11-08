// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockXorCellParameter(technology: Option[ClockXorTechnologyParameter] = None) extends Parameter
given upickle.default.ReadWriter[ClockXorCellParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockXorCellParameter]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, ClockXorCellParameter] = Right(
    upickle.default.read[ClockXorCellParameter](strs.head)
  )

// Both behavior and technology architectures implement this fixed interface.
class ClockXorCellIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Clock())
  val b        = Flipped(Clock())
  val outClock = Aligned(Clock())

// Keep clocks typed at the public boundary. The choice itself switches plain wires,
// which CIRCT can preserve as Verilog conditional instances.
private[clock] class ClockXorChoiceIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Bool())
  val b        = Flipped(Bool())
  val outClock = Aligned(Bool())

class ClockXorChoiceLayers(parameter: Parameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ClockXorChoiceProbe(parameter: Parameter) extends DVBundle[Parameter, ClockXorChoiceLayers](parameter)

class ClockXorCellLayers(parameter: ClockXorCellParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ClockXorCellProbe(parameter: ClockXorCellParameter)
    extends DVBundle[ClockXorCellParameter, ClockXorCellLayers](parameter)

case class ClockXorCellVerilogParameter() extends VerilogParameter

@generator
object ClockXorCell extends Generator[ClockXorCellParameter, ClockXorCellLayers, ClockXorCellIO, ClockXorCellProbe]:
  def architecture(parameter: ClockXorCellParameter) =
    val io       = summon[Interface[ClockXorCellIO]]
    val instance = parameter.technology match
      case Some(binding) =>
        InstanceChoice[ClockXorChoiceLayers, ClockXorChoiceIO, ClockXorChoiceProbe](
          Seq(
            "Behavior"   -> ClockXorBehaviorArchitecture.architecture(parameter.copy(technology = None)),
            "Technology" -> ClockXorTechnology.architecture(binding)
          )
        )
      case None          =>
        InstanceChoice[ClockXorChoiceLayers, ClockXorChoiceIO, ClockXorChoiceProbe](
          Seq(
            "Behavior" -> ClockXorBehaviorArchitecture.architecture(parameter)
          )
        )
    instance.io.a := io.a.asBool
    instance.io.b := io.b.asBool
    io.outClock   := instance.io.outClock.asClock

    layer("Verification"):
      Assert((io.outClock.asBool === (io.a.asBool ^ io.b.asBool)).I, "clock_xor_function")

@generator
private[clock] object ClockXorBehaviorArchitecture
    extends Generator[ClockXorCellParameter, ClockXorChoiceLayers, ClockXorChoiceIO, ClockXorChoiceProbe]:
  def architecture(parameter: ClockXorCellParameter) =
    val io       = summon[Interface[ClockXorChoiceIO]]
    val instance = ClockXorBehavior.instantiate(parameter)
    instance.io.a := io.a
    instance.io.b := io.b
    io.outClock   := instance.io.outClock

@generator
private[clock] object ClockXorBehavior
    extends VerilogWrapper[
      ClockXorCellParameter,
      ClockXorChoiceLayers,
      ClockXorChoiceIO,
      ClockXorChoiceProbe,
      ClockXorCellVerilogParameter
    ]:
  override def moduleName(parameter: ClockXorCellParameter):     String              = verilogModuleName(parameter)
  def verilogModuleName(parameter:   ClockXorCellParameter):     String              = "ClockXorBehavior"
  def verilogParameter(parameter:    ClockXorCellParameter) = ClockXorCellVerilogParameter()
  override def verilogSources(parameter: ClockXorCellParameter): Map[String, String] =
    val name = verilogModuleName(parameter)
    Map(s"$name.sv" -> s"""module $name(input wire a, input wire b, output wire outClock);
                          |  assign outClock = a ^ b;
                          |endmodule
                          |""".stripMargin)

case class ClockXorTechnologyParameter(
  moduleName: String,
  a:          String = "a",
  b:          String = "b",
  outClock:   String = "outClock",
  sources:    Map[String, String] = Map.empty)
    extends Parameter:
  require(moduleName.nonEmpty, "technology module name must not be empty")
  private val pins = Seq(a, b, outClock)
  require(pins.forall(_.nonEmpty) && pins.distinct.size == pins.size, "technology pins must be nonempty and distinct")

given upickle.default.ReadWriter[ClockXorTechnologyParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockXorTechnologyParameter]:
  def shortName = "xor-cell"
  def read(strs: Seq[String]): Right[Nothing, ClockXorTechnologyParameter] = Right(
    upickle.default.read[ClockXorTechnologyParameter](strs.head)
  )

private[clock] class ClockXorTechnologyIO(parameter: ClockXorTechnologyParameter) extends HWRecord(parameter):
  Flipped(parameter.a, Bool())
  Flipped(parameter.b, Bool())
  Aligned(parameter.outClock, Bool())

@generator
private[clock] object ClockXorTechnology
    extends Generator[ClockXorTechnologyParameter, ClockXorChoiceLayers, ClockXorChoiceIO, ClockXorChoiceProbe]:
  def architecture(parameter: ClockXorTechnologyParameter) =
    val io       = summon[Interface[ClockXorChoiceIO]]
    val instance = ClockXorTechnologyWrapper.instantiate(parameter)
    instance.io.field[Bool](parameter.a) := io.a
    instance.io.field[Bool](parameter.b) := io.b
    io.outClock                          := instance.io.field[Bool](parameter.outClock)

@generator
private[clock] object ClockXorTechnologyWrapper
    extends VerilogWrapper[
      ClockXorTechnologyParameter,
      ClockXorChoiceLayers,
      ClockXorTechnologyIO,
      ClockXorChoiceProbe,
      ClockXorCellVerilogParameter
    ]:
  override def moduleName(parameter:     ClockXorTechnologyParameter): String              = parameter.moduleName
  def verilogModuleName(parameter:       ClockXorTechnologyParameter): String              = parameter.moduleName
  def verilogParameter(parameter:        ClockXorTechnologyParameter) = ClockXorCellVerilogParameter()
  override def verilogSources(parameter: ClockXorTechnologyParameter): Map[String, String] = parameter.sources
