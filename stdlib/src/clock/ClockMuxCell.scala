// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockMuxCellParameter(technology: Option[ClockMuxTechnologyParameter] = None) extends Parameter
given upickle.default.ReadWriter[ClockMuxCellParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockMuxCellParameter]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, ClockMuxCellParameter] = Right(
    upickle.default.read[ClockMuxCellParameter](strs.head)
  )

// Both behavior and technology architectures implement this fixed interface.
class ClockMuxCellIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Clock())
  val b        = Flipped(Clock())
  val select   = Flipped(Bool())
  val outClock = Aligned(Clock())

// Keep clocks typed at the public boundary. The choice itself switches plain wires,
// which CIRCT can preserve as Verilog conditional instances.
private[clock] class ClockMuxChoiceIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Bool())
  val b        = Flipped(Bool())
  val select   = Flipped(Bool())
  val outClock = Aligned(Bool())

class ClockMuxChoiceLayers(parameter: Parameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ClockMuxChoiceProbe(parameter: Parameter) extends DVBundle[Parameter, ClockMuxChoiceLayers](parameter)

class ClockMuxCellLayers(parameter: ClockMuxCellParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ClockMuxCellProbe(parameter: ClockMuxCellParameter)
    extends DVBundle[ClockMuxCellParameter, ClockMuxCellLayers](parameter)

case class ClockMuxCellVerilogParameter() extends VerilogParameter

@generator
object ClockMuxCell extends Generator[ClockMuxCellParameter, ClockMuxCellLayers, ClockMuxCellIO, ClockMuxCellProbe]:
  def architecture(parameter: ClockMuxCellParameter) =
    val io       = summon[Interface[ClockMuxCellIO]]
    val instance = parameter.technology match
      case Some(binding) =>
        InstanceChoice[ClockMuxChoiceLayers, ClockMuxChoiceIO, ClockMuxChoiceProbe](
          Seq(
            "Behavior"   -> ClockMuxBehaviorArchitecture.architecture(parameter.copy(technology = None)),
            "Technology" -> ClockMuxTechnology.architecture(binding)
          )
        )
      case None          =>
        InstanceChoice[ClockMuxChoiceLayers, ClockMuxChoiceIO, ClockMuxChoiceProbe](
          Seq(
            "Behavior" -> ClockMuxBehaviorArchitecture.architecture(parameter)
          )
        )
    instance.io.a := io.a.asBool
    instance.io.b      := io.b.asBool
    instance.io.select := io.select
    io.outClock        := instance.io.outClock.asClock

    layer("Verification"):
      Assert((io.outClock.asBool === (io.select ? (io.b.asBool, io.a.asBool))).I, "clock_mux_function")

@generator
private[clock] object ClockMuxBehaviorArchitecture
    extends Generator[ClockMuxCellParameter, ClockMuxChoiceLayers, ClockMuxChoiceIO, ClockMuxChoiceProbe]:
  def architecture(parameter: ClockMuxCellParameter) =
    val io       = summon[Interface[ClockMuxChoiceIO]]
    val instance = ClockMuxBehavior.instantiate(parameter)
    instance.io.a      := io.a
    instance.io.b      := io.b
    instance.io.select := io.select
    io.outClock        := instance.io.outClock

@generator
private[clock] object ClockMuxBehavior
    extends VerilogWrapper[
      ClockMuxCellParameter,
      ClockMuxChoiceLayers,
      ClockMuxChoiceIO,
      ClockMuxChoiceProbe,
      ClockMuxCellVerilogParameter
    ]:
  override def moduleName(parameter: ClockMuxCellParameter):     String              = verilogModuleName(parameter)
  def verilogModuleName(parameter:   ClockMuxCellParameter):     String              = "ClockMuxBehavior"
  def verilogParameter(parameter:    ClockMuxCellParameter) = ClockMuxCellVerilogParameter()
  override def verilogSources(parameter: ClockMuxCellParameter): Map[String, String] =
    val name = verilogModuleName(parameter)
    Map(s"$name.sv" -> s"""module $name(input wire a, input wire b, input wire select, output wire outClock);
                          |  assign outClock = select ? b : a;
                          |endmodule
                          |""".stripMargin)

case class ClockMuxTechnologyParameter(
  moduleName: String,
  a:          String = "a",
  b:          String = "b",
  select:     String = "select",
  outClock:   String = "outClock",
  sources:    Map[String, String] = Map.empty)
    extends Parameter:
  require(moduleName.nonEmpty, "technology module name must not be empty")
  private val pins = Seq(a, b, select, outClock)
  require(pins.forall(_.nonEmpty) && pins.distinct.size == pins.size, "technology pins must be nonempty and distinct")

given upickle.default.ReadWriter[ClockMuxTechnologyParameter] = upickle.default.macroRW
given mainargs.TokensReader.Simple[ClockMuxTechnologyParameter]:
  def shortName = "mux-cell"
  def read(strs: Seq[String]): Right[Nothing, ClockMuxTechnologyParameter] = Right(
    upickle.default.read[ClockMuxTechnologyParameter](strs.head)
  )

private[clock] class ClockMuxTechnologyIO(parameter: ClockMuxTechnologyParameter) extends HWRecord(parameter):
  Flipped(parameter.a, Bool())
  Flipped(parameter.b, Bool())
  Flipped(parameter.select, Bool())
  Aligned(parameter.outClock, Bool())

@generator
private[clock] object ClockMuxTechnology
    extends Generator[ClockMuxTechnologyParameter, ClockMuxChoiceLayers, ClockMuxChoiceIO, ClockMuxChoiceProbe]:
  def architecture(parameter: ClockMuxTechnologyParameter) =
    val io       = summon[Interface[ClockMuxChoiceIO]]
    val instance = ClockMuxTechnologyWrapper.instantiate(parameter)
    instance.io.field[Bool](parameter.a)      := io.a
    instance.io.field[Bool](parameter.b)      := io.b
    instance.io.field[Bool](parameter.select) := io.select
    io.outClock                               := instance.io.field[Bool](parameter.outClock)

@generator
private[clock] object ClockMuxTechnologyWrapper
    extends VerilogWrapper[
      ClockMuxTechnologyParameter,
      ClockMuxChoiceLayers,
      ClockMuxTechnologyIO,
      ClockMuxChoiceProbe,
      ClockMuxCellVerilogParameter
    ]:
  override def moduleName(parameter:     ClockMuxTechnologyParameter): String              = parameter.moduleName
  def verilogModuleName(parameter:       ClockMuxTechnologyParameter): String              = parameter.moduleName
  def verilogParameter(parameter:        ClockMuxTechnologyParameter) = ClockMuxCellVerilogParameter()
  override def verilogSources(parameter: ClockMuxTechnologyParameter): Map[String, String] = parameter.sources
