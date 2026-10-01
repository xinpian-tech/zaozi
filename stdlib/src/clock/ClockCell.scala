// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

enum ClockCellKind derives upickle.default.ReadWriter:
  case Buffer, Inverter, Or, Mux, Xor, GatePositive, GateNegative

given mainargs.TokensReader.Simple[ClockCellKind]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, ClockCellKind] = Right(ClockCellKind.valueOf(strs.head))

case class ClockCellParameter(kind: ClockCellKind, library: ClockCellLibrary = ClockCellLibrary()) extends Parameter:
  require(library.implements(kind), s"clock role $kind is used but no declared cell implements it")
  val binary = Set(ClockCellKind.Or, ClockCellKind.Mux, ClockCellKind.Xor)(kind)
  val gate   = Set(ClockCellKind.GatePositive, ClockCellKind.GateNegative)(kind)

given upickle.default.ReadWriter[ClockCellParameter] = upickle.default.macroRW

class ClockCellIO(parameter: ClockCellParameter) extends HWBundle(parameter):
  val a        = Flipped(Clock())
  val b        = Option.when(parameter.binary)(Flipped(Clock()))
  val select   = Option.when(parameter.kind == ClockCellKind.Mux)(Flipped(Bool()))
  val enable   = Option.when(parameter.gate)(Flipped(Bool()))
  val outClock = Aligned(Clock())

class ClockCellLayers(parameter: ClockCellParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ClockCellProbe(parameter: ClockCellParameter) extends DVBundle[ClockCellParameter, ClockCellLayers](parameter)

case class ClockCellVerilogParameter() extends VerilogParameter

/** The behavioral model of a clock role. */
@generator
object ClockCell
    extends VerilogWrapper[ClockCellParameter, ClockCellLayers, ClockCellIO, ClockCellProbe, ClockCellVerilogParameter]:
  override def moduleName(parameter: ClockCellParameter): String = verilogModuleName(parameter)
  def verilogModuleName(parameter:   ClockCellParameter) = s"ZaoziClock${parameter.kind}"
  def verilogParameter(parameter:    ClockCellParameter) = ClockCellVerilogParameter()

  /** Instantiates a clock role; with declared cells, the `ClockCellIO` option picks `Model` or `Library`. */
  def role(
    kind:    ClockCellKind,
    library: ClockCellLibrary
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Instance[ClockCellIO, ClockCellProbe] =
    val parameter = ClockCellParameter(kind, library)
    if library.cells.isEmpty then ClockCell.instantiate(parameter)
    else
      InstanceChoice[ClockCellLayers, ClockCellIO, ClockCellProbe](
        Seq(
          "Model"   -> ClockCellModel.architecture(parameter),
          "Library" -> ClockCellNetwork.architecture(parameter)
        )
      )

/** The behavioral model of a clock role as an architecture. */
@generator
object ClockCellModel extends Generator[ClockCellParameter, ClockCellLayers, ClockCellIO, ClockCellProbe]:
  def architecture(parameter: ClockCellParameter) =
    val io    = summon[Interface[ClockCellIO]]
    val model = ClockCell.instantiate(parameter)
    model.io.a  := io.a
    model.io.b.foreach(_ := io.b.get)
    model.io.select.foreach(_ := io.select.get)
    model.io.enable.foreach(_ := io.enable.get)
    io.outClock := model.io.outClock

/** A clock role built from declared cells. */
@generator
object ClockCellNetwork extends Generator[ClockCellParameter, ClockCellLayers, ClockCellIO, ClockCellProbe]:
  def architecture(parameter: ClockCellParameter) =
    val io      = summon[Interface[ClockCellIO]]
    val library = parameter.library
    def port(name: String):                       Referable[Bool]                             = name match
      case "a"      => io.a.asBool
      case "b"      => io.b.get.asBool
      case "select" => io.select.get
      case "0"      => false.B
      case "1"      => true.B
    def declared(name: String, instance: String): Instance[DeclaredCellIO, DeclaredCellProbe] =
      DeclaredCell.instantiate(DeclaredCellParameter(library.cells.find(_.name == name).get))(
        using summon[Arena],
        summon[Context],
        summon[Block],
        summon[sourcecode.File],
        summon[sourcecode.Line],
        sourcecode.Name.Machine(instance),
        summon[InstanceContext]
      )
    def combinational(
      cell:     ClockCellDeclaration,
      instance: Instance[DeclaredCellIO, DeclaredCellProbe]
    )(source:   String => Referable[Bool]
    ): Referable[Bool] =
      cell.inputs.foreach(pin => instance.io.field[Bool](pin) := source(pin))
      instance.io.field[Bool](cell.output)
    library.roles(parameter.kind) match
      case ClockRole.Cell(name, pins) =>
        val cell     = library.cells.find(_.name == name).get
        val instance = declared(name, "libraryCell")
        val ports    = pins.map(_.swap)
        cell.sequential match
          case Some(gate) =>
            instance.io.field[Clock](gate.clock) := io.a
            instance.io.field[Bool](gate.enable) := io.enable.get
            gate.test.foreach(instance.io.field[Bool](_) := false.B)
            io.outClock                          := instance.io.field[Clock](gate.output)
          case None       =>
            io.outClock := combinational(cell, instance)(pin =>
              ports.get(pin).fold(port(cell.tie(pin).toString))(port)
            ).asClock
      case ClockRole.Inverted(gate)   =>
        val inverted = ClockCellNetwork.instantiate(ClockCellParameter(ClockCellKind.Inverter, library))
        val gated    = ClockCellNetwork.instantiate(ClockCellParameter(gate, library))
        val restored = ClockCellNetwork.instantiate(ClockCellParameter(ClockCellKind.Inverter, library))
        inverted.io.a       := io.a
        gated.io.a          := inverted.io.outClock
        gated.io.enable.get := io.enable.get
        restored.io.a       := gated.io.outClock
        io.outClock         := restored.io.outClock

case class DeclaredCellParameter(cell: ClockCellDeclaration) extends Parameter

given upickle.default.ReadWriter[DeclaredCellParameter] = upickle.default.macroRW

class DeclaredCellIO(parameter: DeclaredCellParameter) extends HWRecord(parameter):
  parameter.cell.sequential match
    case Some(gate) =>
      Flipped(gate.clock, Clock())
      Flipped(gate.enable, Bool())
      gate.test.foreach(Flipped(_, Bool()))
      Aligned(gate.output, Clock())
    case None       =>
      parameter.cell.inputs.foreach(Flipped(_, Bool()))
      Aligned(parameter.cell.output, Bool())

class DeclaredCellLayers(parameter: DeclaredCellParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class DeclaredCellProbe(parameter: DeclaredCellParameter)
    extends DVBundle[DeclaredCellParameter, DeclaredCellLayers](parameter)

/** A declared library cell, provided by the cell library at synthesis. */
@generator
object DeclaredCell
    extends VerilogWrapper[
      DeclaredCellParameter,
      DeclaredCellLayers,
      DeclaredCellIO,
      DeclaredCellProbe,
      ClockCellVerilogParameter
    ]:
  override def moduleName(parameter: DeclaredCellParameter): String = parameter.cell.name
  def verilogModuleName(parameter:   DeclaredCellParameter) = parameter.cell.name
  def verilogParameter(parameter:    DeclaredCellParameter) = ClockCellVerilogParameter()
