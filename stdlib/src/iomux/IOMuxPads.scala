// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.iomux

import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

case class IOMuxPadEnable(pin: String, activeHigh: Boolean)
given upickle.default.ReadWriter[IOMuxPadEnable] = upickle.default.macroRW

// The model has the same ports and control semantics as the library macro.
case class IOMuxPadCell(
  cell:         IOMuxCell,
  model:        String,
  pad:          String,
  inputValue:   Option[String],
  outputValue:  Option[String],
  inputEnable:  Option[IOMuxPadEnable],
  outputEnable: Option[IOMuxPadEnable],
  tie: Map[String, Boolean] = Map.empty):
  require(cell.hasReceiver == inputValue.nonEmpty, s"cell ${cell.name} receiver does not match inputValue")
  private[iomux] val controls = cell.pull.toSeq.flatMap(_.pins) ++ cell.control.flatMap(_.pins)
  private[iomux] val inputs   =
    outputValue.toSeq ++ inputEnable.map(_.pin) ++ outputEnable.map(_.pin) ++ controls ++ tie.keys
  private val ports           = Seq(pad) ++ inputValue ++ inputs
  require(ports.distinct.size == ports.size, s"cell ${cell.name} port roles overlap")

given upickle.default.ReadWriter[IOMuxPadCell] = upickle.default.macroRW

case class IOMuxPadLibrary(cells: Seq[IOMuxPadCell]):
  val iomuxCells: Seq[IOMuxCell] = cells.map(_.cell)

given upickle.default.ReadWriter[IOMuxPadLibrary] = upickle.default.macroRW

case class IOMuxPadsParameter(iomux: IOMuxParameter, library: IOMuxPadLibrary) extends Parameter:
  require(iomux.cell.nonEmpty, "pads need a cell selection for every pin")
  require(iomux.cells == library.iomuxCells, "IOMux and pads must use the same cell library")
  private[iomux] val cells = iomux.pinCell.map(name => library.cells.find(_.cell.name == name).get)

given upickle.default.ReadWriter[IOMuxPadsParameter] = upickle.default.macroRW

class IOMuxPadsIO(parameter: IOMuxPadsParameter) extends HWRecord(parameter):
  Flipped("inputEnable", Bits(parameter.iomux.pinCount))
  Flipped("outputValue", Bits(parameter.iomux.pinCount))
  Flipped("outputEnable", Bits(parameter.iomux.pinCount))
  Aligned("inputValue", Bits(parameter.iomux.pinCount))
  parameter.cells.zipWithIndex.foreach: (cell, pin) =>
    Inout(s"pad_$pin", Bits(1))
    cell.cell.pull.foreach(pull => Flipped(s"pin_${pin}_pull", Bits(pull.width)))
    cell.cell.control.foreach: control =>
      val index = parameter.iomux.cell.get.controlNames.indexOf(control.name)
      Flipped(s"pin_${pin}_control_$index", Bits(control.table.width))

class IOMuxPadsLayers(parameter: IOMuxPadsParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IOMuxPadsProbe(parameter: IOMuxPadsParameter) extends DVBundle[IOMuxPadsParameter, IOMuxPadsLayers](parameter)

object IOMuxPads:
  def apply(
    parameter: IOMuxPadsParameter
  )(iomux:     Interface[IOMuxIO]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Instance[IOMuxPadsIO, IOMuxPadsProbe] =
    val pads = InstanceChoice[IOMuxPadsLayers, IOMuxPadsIO, IOMuxPadsProbe](
      Seq(
        "Model"   -> IOMuxPadsModel.architecture(parameter),
        "Library" -> IOMuxPadsLibrary.architecture(parameter)
      )
    )
    pads.io.field[Bits]("inputEnable") := iomux.cellInputEnable
    pads.io.field[Bits]("outputValue")  := iomux.cellOutputValue
    pads.io.field[Bits]("outputEnable") := iomux.cellOutputEnable
    iomux.cellInputValue                := pads.io.field[Bits]("inputValue")
    parameter.cells.zipWithIndex.foreach: (cell, pin) =>
      val names = cell.cell.pull.map(_ => s"pin_${pin}_pull").toSeq ++ cell.cell.control.map: control =>
        s"pin_${pin}_control_${parameter.iomux.cell.get.controlNames.indexOf(control.name)}"
      names.foreach(name => pads.io.field[Bits](name) := iomux.cell.get.field[Bits](name))
    pads

  private[iomux] def build(
    parameter:  IOMuxPadsParameter,
    moduleName: IOMuxPadCell => String
  )(
    using Arena,
    Context,
    Block,
    Interface[IOMuxPadsIO],
    InstanceContext
  ): Unit =
    val io     = summon[Interface[IOMuxPadsIO]]
    val values = parameter.cells.zipWithIndex.map: (cell, pin) =>
      val pad = IOMuxPadMacro.instantiate(IOMuxPadMacroParameter(cell, moduleName(cell)))(
        using summon[Arena],
        summon[Context],
        summon[Block],
        summon[sourcecode.File],
        summon[sourcecode.Line],
        sourcecode.Name.Machine(s"pad$pin"),
        summon[InstanceContext]
      )
      attach(io.field[Analog](s"pad_$pin"), pad.io.field[Analog](cell.pad))
      cell.outputValue.foreach(name => pad.io.field[Bool](name) := io.field[Bits]("outputValue").bit(pin))
      Seq(cell.inputEnable -> "inputEnable", cell.outputEnable -> "outputEnable").foreach: (enable, field) =>
        enable.foreach: port =>
          val value = io.field[Bits](field).bit(pin)
          pad.io.field[Bool](port.pin) := (if port.activeHigh then value else !value)
      cell.cell.pull.foreach: pull =>
        pull.pins.zipWithIndex.foreach: (name, bit) =>
          pad.io.field[Bool](name) := io.field[Bits](s"pin_${pin}_pull").bit(bit)
      cell.cell.control.foreach: control =>
        val index = parameter.iomux.cell.get.controlNames.indexOf(control.name)
        control.pins.zipWithIndex.foreach: (name, bit) =>
          pad.io.field[Bool](name) := io.field[Bits](s"pin_${pin}_control_$index").bit(bit)
      cell.tie.foreach((name, value) => pad.io.field[Bool](name) := value.B)
      cell.inputValue.fold(false.B: Referable[Bool])(pad.io.field[Bool])
    io.field[Bits]("inputValue") := values.toVec.asBits

@generator
object IOMuxPadsModel extends Generator[IOMuxPadsParameter, IOMuxPadsLayers, IOMuxPadsIO, IOMuxPadsProbe]:
  def architecture(parameter: IOMuxPadsParameter) = IOMuxPads.build(parameter, _.model)

@generator
object IOMuxPadsLibrary extends Generator[IOMuxPadsParameter, IOMuxPadsLayers, IOMuxPadsIO, IOMuxPadsProbe]:
  def architecture(parameter: IOMuxPadsParameter) = IOMuxPads.build(parameter, _.cell.name)

case class IOMuxPadMacroParameter(cell: IOMuxPadCell, moduleName: String) extends Parameter

given upickle.default.ReadWriter[IOMuxPadMacroParameter] = upickle.default.macroRW

class IOMuxPadMacroIO(parameter: IOMuxPadMacroParameter) extends HWRecord(parameter):
  Inout(parameter.cell.pad, Bits(1))
  parameter.cell.inputs.foreach(name => Flipped(name, Bool()))
  parameter.cell.inputValue.foreach(name => Aligned(name, Bool()))

class IOMuxPadMacroLayers(parameter: IOMuxPadMacroParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IOMuxPadMacroProbe(parameter: IOMuxPadMacroParameter)
    extends DVBundle[IOMuxPadMacroParameter, IOMuxPadMacroLayers](parameter)

case class IOMuxPadMacroVerilogParameter() extends VerilogParameter

@generator
object IOMuxPadMacro
    extends VerilogWrapper[
      IOMuxPadMacroParameter,
      IOMuxPadMacroLayers,
      IOMuxPadMacroIO,
      IOMuxPadMacroProbe,
      IOMuxPadMacroVerilogParameter
    ]:
  override def moduleName(parameter: IOMuxPadMacroParameter): String = parameter.moduleName
  def verilogModuleName(parameter:   IOMuxPadMacroParameter) = parameter.moduleName
  def verilogParameter(parameter:    IOMuxPadMacroParameter) = IOMuxPadMacroVerilogParameter()

given mainargs.TokensReader.Simple[IOMuxParameter]:
  def shortName = "iomux"
  def read(strs: Seq[String]): Right[Nothing, IOMuxParameter] = Right(upickle.default.read[IOMuxParameter](strs.head))

given mainargs.TokensReader.Simple[IOMuxPadLibrary]:
  def shortName = "library"
  def read(strs: Seq[String]): Right[Nothing, IOMuxPadLibrary] = Right(upickle.default.read[IOMuxPadLibrary](strs.head))

given mainargs.TokensReader.Simple[IOMuxPadCell]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, IOMuxPadCell] = Right(upickle.default.read[IOMuxPadCell](strs.head))
