// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

// Paths describe clock behavior; the tree supplies the implementation library.
case class ClockPathGate(positive: Boolean, clockDuringReset: Boolean)
given upickle.default.ReadWriter[ClockPathGate] = upickle.default.macroRW

case class ClockPathDivider(width: Int, initial: BigInt, clockDuringReset: Boolean, automatic: Boolean):
  require(width > 0, "divider width must be positive")
  require(initial >= 0 && initial.bitLength <= width, "initial divisor must fit the divider width")
given upickle.default.ReadWriter[ClockPathDivider] = upickle.default.macroRW

case class ClockPath(
  gate:    Option[ClockPathGate] = None,
  divider: Option[ClockPathDivider] = None,
  invert:  Boolean = false)

given upickle.default.ReadWriter[ClockPath] = upickle.default.macroRW

case class ClockLink(source: String, path: ClockPath = ClockPath())

given upickle.default.ReadWriter[ClockLink] = upickle.default.macroRW

enum ClockSelection derives upickle.default.ReadWriter:
  case Raw
  case GlitchFree(stages: Int, clockDuringReset: Boolean)

case class ClockTarget(
  name:      String,
  links:     Seq[ClockLink],
  selection: Option[ClockSelection] = None,
  path: ClockPath = ClockPath()):
  require(links.nonEmpty, s"clock target $name needs a source")
  require(
    selection.isDefined == (links.size > 1),
    s"clock target $name needs selection exactly when it has multiple links"
  )
  val selectWidth = BigInt(links.size - 1).bitLength.max(1)

given upickle.default.ReadWriter[ClockTarget] = upickle.default.macroRW

given mainargs.TokensReader.Simple[ClockTarget]:
  def shortName = "target"
  def read(strs: Seq[String]): Right[Nothing, ClockTarget] = Right(upickle.default.read[ClockTarget](strs.head))

case class ClockTreeParameter(
  inputs:   Seq[String],
  targets:  Seq[ClockTarget],
  gate:     ClockGateTechnologyMapping = ClockGateTechnologyMapping(),
  mux:      ClockMuxCellParameter = ClockMuxCellParameter(),
  or:       ClockOrCellParameter = ClockOrCellParameter(),
  xor:      ClockXorCellParameter = ClockXorCellParameter(),
  inverter: ClockInverterCellParameter = ClockInverterCellParameter())
    extends Parameter:
  val names = inputs ++ targets.map(_.name)
  require(names.forall(_.nonEmpty), "clock resource names must not be empty")
  require(names.distinct.size == names.size, "clock resource names must be unique")
  targets.foreach: target =>
    target.links.foreach(link => require(names.contains(link.source), s"clock source ${link.source} is not declared"))

  @annotation.tailrec
  private def order(remaining: Seq[ClockTarget], available: Set[String], result: Seq[ClockTarget]): Seq[ClockTarget] =
    if remaining.isEmpty then result
    else
      val (ready, waiting) = remaining.partition(_.links.forall(link => available(link.source)))
      require(ready.nonEmpty, "clock targets contain a dependency cycle")
      order(waiting, available ++ ready.map(_.name), result ++ ready)

  val orderedTargets = order(targets, inputs.toSet, Seq.empty)
  val targetIndices  = targets.map(_.name).zipWithIndex.toMap

  def bindings: ujson.Value = ujson.Obj(
    "inputs"  -> ujson.Arr.from(inputs.zipWithIndex.map((name, index) => ujson.Obj("name" -> name, "port" -> index))),
    "targets" -> ujson.Arr.from(targets.zipWithIndex.map: (target, index) =>
      ujson.Obj("name" -> target.name, "port" -> index, "sources" -> ujson.Arr.from(target.links.map(_.source))))
  )

given upickle.default.ReadWriter[ClockTreeParameter] = upickle.default.macroRW

class ClockTreeLayers(parameter: ClockTreeParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"), Layer("Progress"))

class ClockGateControl extends Bundle:
  val resetN     = Flipped(Reset())
  val enable     = Flipped(Bool())
  val testEnable = Flipped(Bool())

class ClockDividerControl(parameter: ClockPathDivider) extends Bundle:
  val resetN     = Flipped(Reset())
  val enable     = Flipped(Bool())
  val testEnable = Flipped(Bool())
  val divisor    = Flipped(Bits(parameter.width))
  val valid      = Option.when(!parameter.automatic)(Flipped(Bool()))
  val ready      = Option.when(!parameter.automatic)(Aligned(Bool()))
  val count      = Aligned(UInt(parameter.width))

class ClockPathControl(path: ClockPath) extends Bundle:
  val gate    = Option.when(path.gate.nonEmpty)(Aligned(new ClockGateControl))
  val divider = Option.when(path.divider.nonEmpty)(Aligned(new ClockDividerControl(path.divider.get)))

class ClockTargetControl(target: ClockTarget) extends Record:
  target.links.zipWithIndex.foreach((link, index) => Aligned(s"link_$index", new ClockPathControl(link.path)))
  Aligned("path", new ClockPathControl(target.path))
  target.selection.foreach: selection =>
    Flipped("select", Bits(target.selectWidth))
    selection match
      case ClockSelection.Raw              => ()
      case ClockSelection.GlitchFree(_, _) =>
        Flipped("resetN", Reset())
        Flipped("testEnable", Bool())
        Flipped("testClock", Clock())

class ClockTreeControls(parameter: ClockTreeParameter) extends Record:
  parameter.targets.zipWithIndex.foreach((target, index) => Aligned(s"target_$index", new ClockTargetControl(target)))

class ClockTreeIO(parameter: ClockTreeParameter) extends HWBundle(parameter):
  val inputs   = Flipped(Vec(parameter.inputs.size, Clock()))
  val controls = Aligned(new ClockTreeControls(parameter))
  val outputs  = Aligned(Vec(parameter.targets.size, Clock()))

class ClockTreeProbe(parameter: ClockTreeParameter) extends DVBundle[ClockTreeParameter, ClockTreeLayers](parameter)

@generator
object ClockTree extends Generator[ClockTreeParameter, ClockTreeLayers, ClockTreeIO, ClockTreeProbe]:
  def main(args: Array[String]): Unit = args.toList match
    case "bindings" :: config :: Nil =>
      println(upickle.default.read[ClockTreeParameter](os.read(os.Path(config, os.pwd))).bindings.render())
    case _                           => this.mainImpl(args)

  def architecture(parameter: ClockTreeParameter) =
    val io = summon[Interface[ClockTreeIO]]

    def named[T](name: String)(body: sourcecode.Name.Machine ?=> T): T =
      body(
        using sourcecode.Name.Machine(name)
      )
    def process(input: Referable[Clock], path: ClockPath, control: Ref[ClockPathControl], name: String)
      : Referable[Clock] =
      val gated   = path.gate.fold(input): config =>
        val ports = control.gate.get
        val cell  = named(s"${name}_gate")(
          ClockGate.instantiate(
            ClockGateParameter(
              parameter.gate.cell(config.positive),
              config.clockDuringReset,
              parameter.mux
            )
          )
        )
        cell.io.clock      := input
        cell.io.resetN     := ports.resetN
        cell.io.enable     := ports.enable
        cell.io.testEnable := ports.testEnable
        cell.io.output
      val divided = path.divider.fold(gated): config =>
        val ports = control.divider.get
        val cell  = named(s"${name}_divider")(
          ClockDivider.instantiate(
            ClockDividerParameter(
              config.width,
              config.initial,
              config.clockDuringReset,
              config.automatic,
              parameter.gate.cell(true),
              parameter.mux,
              parameter.xor
            )
          )
        )
        cell.io.clock      := gated
        cell.io.resetN     := ports.resetN
        cell.io.enable     := ports.enable
        cell.io.testEnable := ports.testEnable
        cell.io.divisor    := ports.divisor
        cell.io.valid.foreach(_ := ports.valid.get)
        ports.ready.foreach(_ := cell.io.ready.get)
        ports.count        := cell.io.count
        cell.io.output
      val result  = if path.invert then
        val cell = named(s"${name}_inverter")(
          ClockInverterCell.instantiate(parameter.inverter)
        )
        cell.io.a := divided
        cell.io.outClock
      else divided
      layer("Verification"):
        val bypass       = path.gate.fold(true.B: Referable[Bool])(_ => control.gate.get.testEnable) &
          path.divider.fold(true.B: Referable[Bool])(_ => control.divider.get.testEnable)
        val expected     = if path.invert then !input.asBool else input.asBool
        Assert((!bypass | (result.asBool === expected)).I, s"${name}_test_path_polarity")
        given ClockEvent = posedge(input)
        Cover(bypass.S, true.B, s"${name}_test_path")
        path.gate.foreach:    _ =>
          val gate = control.gate.get
          Cover(
            (!gate.enable).S.##*(gate.enable.S).##*((!gate.enable).S),
            gate.resetN.asBool,
            s"${name}_gate_stop_start_stop"
          )
        path.divider.foreach: config =>
          val divider = control.divider.get
          Cover(
            (divider.divisor === BigInt(1).B(config.width)).S
              .##*((divider.divisor.asUInt > BigInt(1).U(config.width)).S),
            divider.resetN.asBool,
            s"${name}_bypass_to_divided"
          )
      layer("Progress"):
        given ClockEvent   = posedge(input)
        val gateEnabled    = path.gate.fold(true.B: Referable[Bool]): _ =>
          val ports = control.gate.get
          ports.resetN.asBool & ports.enable & !ports.testEnable
        val dividerEnabled = path.divider.fold(true.B: Referable[Bool]): _ =>
          val ports = control.divider.get
          ports.resetN.asBool & ports.enable & !ports.testEnable
        val stableDivisor  = path.divider.map: _ =>
          val ports        = control.divider.get
          given ClockScope = ClockScope.posedge(input)
          given ResetScope = ResetScope.asyncActiveLow(ports.resetN)
          val previous     = RegInit(path.divider.get.initial.B(path.divider.get.width))
          previous := ports.divisor
          always((ports.divisor === previous).S)
        val running        = always((gateEnabled & dividerEnabled).S)
        val premise        = stableDivisor.fold(running)(running & _)
        Assert(
          premise implies always(eventually(posedge(result) { true.B.S })),
          true.B,
          s"${name}_enabled_path_keeps_ticking"
        )
      result

    val inputs = parameter.inputs.zipWithIndex.map((name, index) => name -> (io.inputs(index): Referable[Clock])).toMap
    parameter.orderedTargets.foldLeft(inputs): (clocks, target) =>
      val index    = parameter.targetIndices(target.name)
      val control  = io.controls.field[ClockTargetControl](s"target_$index")
      val links    = target.links.zipWithIndex.map: (link, linkIndex) =>
        process(
          clocks(link.source),
          link.path,
          control.field[ClockPathControl](s"link_$linkIndex"),
          s"target_${index}_link_$linkIndex"
        )
      val selected = target.selection.fold(links.head): selection =>
        val select = control.field[Bits]("select")
        selection match
          case ClockSelection.Raw                        =>
            val value = links.zipWithIndex.foldLeft(false.B.asClock: Referable[Clock]): (result, entry) =>
              val (clock, source) = entry
              val cell            =
                named(s"target_${index}_mux_$source")(
                  ClockMuxCell.instantiate(parameter.mux)
                )
              cell.io.a      := result
              cell.io.b      := clock
              cell.io.select := select === BigInt(source).B(target.selectWidth)
              cell.io.outClock
            layer("Verification"):
              given ClockEvent = posedge(links.head)
              links.indices.foreach: source =>
                Assert(
                  ((select =/= BigInt(source).B(target.selectWidth)) |
                    (value.asBool === links(source).asBool)).I,
                  s"target_${index}_selected_path_$source"
                )
                Cover((select === BigInt(source).B(target.selectWidth)).S, true.B, s"target_${index}_select_$source")
              if BigInt(links.size) < (BigInt(1) << target.selectWidth) then
                Assert(
                  ((select.asUInt < BigInt(links.size).U(target.selectWidth)) | !value.asBool).I,
                  s"target_${index}_invalid_selection_stops_clock"
                )
                Cover(
                  (select.asUInt >= BigInt(links.size).U(target.selectWidth)).S,
                  true.B,
                  s"target_${index}_invalid_selection"
                )
            value
          case ClockSelection.GlitchFree(stages, bypass) =>
            val cell =
              named(s"target_${index}_mux")(
                ClockMux.instantiate(
                  ClockMuxParameter(links.size, stages, bypass, parameter.gate.cell(true), parameter.mux, parameter.or)
                )
              )
            links.zipWithIndex.foreach((clock, source) => cell.io.clocks(source) := clock)
            cell.io.resetN := control.field[Reset]("resetN")
            cell.io.select     := select
            cell.io.testEnable := control.field[Bool]("testEnable")
            cell.io.testClock  := control.field[Clock]("testClock")
            layer("Verification"):
              Assert(
                (!control.field[Bool]("testEnable") |
                  (cell.io.output.asBool === control.field[Clock]("testClock").asBool)).I,
                s"target_${index}_selection_test_clock"
              )
            cell.io.output
      val output   = process(selected, target.path, control.field[ClockPathControl]("path"), s"target_$index")
      io.outputs(index) := output
      clocks.updated(target.name, output)
