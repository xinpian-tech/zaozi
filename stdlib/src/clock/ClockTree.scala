// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ClockPath(
  gate:         Option[ClockGateParameter] = None,
  divider:      Option[ClockDividerParameter] = None,
  invert:       Boolean = false,
  gateGuide:    Option[ClockGuideParameter] = None,
  dividerGuide: Option[ClockGuideParameter] = None,
  inverterGuide: Option[ClockGuideParameter] = None):
  require(gateGuide.isEmpty || gate.nonEmpty, "a gate guide needs a gate")
  require(dividerGuide.isEmpty || divider.nonEmpty, "a divider guide needs a divider")
  require(inverterGuide.isEmpty || invert, "an inverter guide needs an inverter")

  private[clock] def instances(prefix: String): Seq[String] =
    Seq(
      Option.when(gate.nonEmpty)(s"${prefix}_gate"),
      Option.when(divider.nonEmpty)(s"${prefix}_divider"),
      Option.when(invert)(s"${prefix}_inverter")
    ).flatten ++
      Seq("gate" -> gateGuide, "divider" -> dividerGuide, "inverter" -> inverterGuide)
        .flatMap((stage, guide) => guide.map(_.instance.getOrElse(s"${prefix}_${stage}_guide")))

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
  path:      ClockPath = ClockPath(),
  muxGuide: Option[ClockGuideParameter] = None):
  require(links.nonEmpty, s"clock target $name needs a source")
  require(
    selection.isDefined == (links.size > 1),
    s"clock target $name needs selection exactly when it has multiple links"
  )
  require(muxGuide.isEmpty || selection.nonEmpty, s"clock target $name mux guide needs a mux")
  val selectWidth = BigInt(links.size - 1).bitLength.max(1)

given upickle.default.ReadWriter[ClockTarget] = upickle.default.macroRW

given mainargs.TokensReader.Simple[ClockTarget]:
  def shortName = "target"
  def read(strs: Seq[String]): Right[Nothing, ClockTarget] = Right(upickle.default.read[ClockTarget](strs.head))

case class ClockTreeParameter(inputs: Seq[String], targets: Seq[ClockTarget]) extends Parameter:
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

  val orderedTargets    = order(targets, inputs.toSet, Seq.empty)
  val targetIndices     = targets.map(_.name).zipWithIndex.toMap
  private val instances = targets.zipWithIndex.flatMap: (target, index) =>
    val prefix = s"target_$index"
    target.links.zipWithIndex.flatMap((link, i) => link.path.instances(s"${prefix}_link_$i")) ++
      target.path.instances(prefix) ++
      Option.when(target.selection.exists(_ != ClockSelection.Raw))(s"${prefix}_mux") ++
      Option
        .when(target.selection.contains(ClockSelection.Raw))(target.links.indices.map(i => s"${prefix}_mux_$i"))
        .toSeq
        .flatten ++
      target.muxGuide.map(_.instance.getOrElse(s"${prefix}_mux_guide"))
  require(instances.distinct.size == instances.size, "clock instance names must be unique")

  def bindings: ujson.Value = ujson.Obj(
    "inputs"  -> ujson.Arr.from(inputs.zipWithIndex.map((name, index) => ujson.Obj("name" -> name, "port" -> index))),
    "targets" -> ujson.Arr.from(targets.zipWithIndex.map: (target, index) =>
      ujson.Obj("name" -> target.name, "port" -> index, "sources" -> ujson.Arr.from(target.links.map(_.source))))
  )

given upickle.default.ReadWriter[ClockTreeParameter] = upickle.default.macroRW

class ClockTreeLayers(parameter: ClockTreeParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ClockGateControl extends Bundle:
  val resetN     = Flipped(Reset())
  val enable     = Flipped(Bool())
  val testEnable = Flipped(Bool())

class ClockDividerControl(parameter: ClockDividerParameter) extends Bundle:
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

    def named[T](name: String)(body: sourcecode.Name.Machine ?=> T):                       T                =
      body(
        using sourcecode.Name.Machine(name)
      )
    def guide(input: Referable[Clock], config: Option[ClockGuideParameter], name: String): Referable[Clock] =
      config.fold(input): parameter =>
        val cell = named(parameter.instance.getOrElse(name))(ClockGuide.instantiate(parameter.copy(instance = None)))
        cell.io.field[Clock](parameter.input) := input
        cell.io.field[Clock](parameter.output)
    def process(input: Referable[Clock], path: ClockPath, control: Ref[ClockPathControl], name: String)
      : Referable[Clock] =
      val gateOutput    = path.gate.fold(input): config =>
        val ports = control.gate.get
        val cell  = named(s"${name}_gate")(ClockGate.instantiate(config))
        cell.io.clock      := input
        cell.io.resetN     := ports.resetN
        cell.io.enable     := ports.enable
        cell.io.testEnable := ports.testEnable
        cell.io.output
      val gated         = guide(gateOutput, path.gateGuide, s"${name}_gate_guide")
      val dividerOutput = path.divider.fold(gated): config =>
        val ports = control.divider.get
        val cell  = named(s"${name}_divider")(ClockDivider.instantiate(config))
        cell.io.clock      := gated
        cell.io.resetN     := ports.resetN
        cell.io.enable     := ports.enable
        cell.io.testEnable := ports.testEnable
        cell.io.divisor    := ports.divisor
        cell.io.valid.foreach(_ := ports.valid.get)
        ports.ready.foreach(_ := cell.io.ready.get)
        ports.count        := cell.io.count
        cell.io.output
      val divided       = guide(dividerOutput, path.dividerGuide, s"${name}_divider_guide")
      val inverted      = if path.invert then
        val cell = named(s"${name}_inverter")(ClockCell.instantiate(ClockCellParameter(ClockCellKind.Inverter)))
        cell.io.a := divided
        cell.io.outClock
      else divided
      guide(inverted, path.inverterGuide, s"${name}_inverter_guide")

    val inputs = parameter.inputs.zipWithIndex.map((name, index) => name -> (io.inputs(index): Referable[Clock])).toMap
    parameter.orderedTargets.foldLeft(inputs): (clocks, target) =>
      val index     = parameter.targetIndices(target.name)
      val control   = io.controls.field[ClockTargetControl](s"target_$index")
      val links     = target.links.zipWithIndex.map: (link, linkIndex) =>
        process(
          clocks(link.source),
          link.path,
          control.field[ClockPathControl](s"link_$linkIndex"),
          s"target_${index}_link_$linkIndex"
        )
      val selected  = target.selection.fold(links.head): selection =>
        val select = control.field[Bits]("select")
        selection match
          case ClockSelection.Raw                        =>
            val value = links.zipWithIndex.foldLeft(false.B.asClock: Referable[Clock]): (result, entry) =>
              val (clock, source) = entry
              val cell            =
                named(s"target_${index}_mux_$source")(ClockCell.instantiate(ClockCellParameter(ClockCellKind.Mux)))
              cell.io.a          := result
              cell.io.b.get      := clock
              cell.io.select.get := select === BigInt(source).B(target.selectWidth)
              cell.io.outClock
            layer("Verification"):
              given ClockEvent = posedge(links.head)
              links.indices.foreach: source =>
                Cover((select === BigInt(source).B(target.selectWidth)).S, true.B, s"target_${index}_select_$source")
              if BigInt(links.size) < (BigInt(1) << target.selectWidth) then
                Cover(
                  (select.asUInt >= BigInt(links.size).U(target.selectWidth)).S,
                  true.B,
                  s"target_${index}_invalid_selection"
                )
            value
          case ClockSelection.GlitchFree(stages, bypass) =>
            val cell =
              named(s"target_${index}_mux")(ClockMux.instantiate(ClockMuxParameter(links.size, stages, bypass)))
            links.zipWithIndex.foreach((clock, source) => cell.io.clocks(source) := clock)
            cell.io.resetN := control.field[Reset]("resetN")
            cell.io.select     := select
            cell.io.testEnable := control.field[Bool]("testEnable")
            cell.io.testClock  := control.field[Clock]("testClock")
            cell.io.output
      val muxOutput = guide(selected, target.muxGuide, s"target_${index}_mux_guide")
      val output    = process(muxOutput, target.path, control.field[ClockPathControl]("path"), s"target_$index")
      io.outputs(index) := output
      clocks.updated(target.name, output)
