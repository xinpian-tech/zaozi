// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.reset

import me.jiuyang.stdlib.default.{SynchronizedReset, SynchronizedResetParameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.dialect.firrtl.operation.RegResetPolarity

case class ResetSource(name: String, activeLow: Boolean)

given upickle.default.ReadWriter[ResetSource] = upickle.default.macroRW

given mainargs.TokensReader.Simple[ResetSource]:
  def shortName = "source"
  def read(strs: Seq[String]): Right[Nothing, ResetSource] = Right(upickle.default.read[ResetSource](strs.head))

enum ResetProcessing derives upickle.default.ReadWriter:
  case Async(clock: String, stages: Int)
  case Pipeline(clock: String, stages: Int)
  case Counter(clock: String, cycles: Int)

  def clockName: String = this match
    case Async(clock, _)    => clock
    case Pipeline(clock, _) => clock
    case Counter(clock, _)  => clock

case class ResetLink(source: String, processing: Option[ResetProcessing] = None)

given upickle.default.ReadWriter[ResetLink] = upickle.default.macroRW

case class ResetTarget(
  name:       String,
  activeLow:  Boolean,
  links:      Seq[ResetLink],
  processing: Option[ResetProcessing] = None)

given upickle.default.ReadWriter[ResetTarget] = upickle.default.macroRW

given mainargs.TokensReader.Simple[ResetTarget]:
  def shortName = "target"
  def read(strs: Seq[String]): Right[Nothing, ResetTarget] = Right(upickle.default.read[ResetTarget](strs.head))

case class ResetTreeReason(clock: String, root: String)

given upickle.default.ReadWriter[ResetTreeReason] = upickle.default.macroRW

given mainargs.TokensReader.Simple[ResetTreeReason]:
  def shortName = "reason"
  def read(strs: Seq[String]): Right[Nothing, ResetTreeReason] = Right(upickle.default.read[ResetTreeReason](strs.head))

case class ResetTreeParameter(
  sources: Seq[ResetSource],
  targets: Seq[ResetTarget],
  reason:  Option[ResetTreeReason] = None)
    extends Parameter:
  val names         = sources.map(_.name) ++ targets.map(_.name)
  require(names.forall(_.nonEmpty), "reset resource names must not be empty")
  require(names.distinct.size == names.size, "reset resource names must be unique")
  val sourceIndices = sources.map(_.name).zipWithIndex.toMap
  targets.foreach: target =>
    target.links.foreach(link =>
      require(sourceIndices.contains(link.source), s"reset source ${link.source} is not declared")
    )
  reason.foreach(record =>
    require(sourceIndices.contains(record.root), s"reset reason root ${record.root} is not declared")
  )
  val processors = targets.flatMap(target => target.links.flatMap(_.processing) ++ target.processing)
  processors.foreach:
    case ResetProcessing.Async(_, stages) =>
      require(stages >= 1, s"reset synchronization stages must be positive: $stages")
    case _                                => ()
  val clocks = (processors.map(_.clockName) ++ reason.map(_.clock)).distinct
  require(clocks.forall(_.nonEmpty), "reset processing clocks must be named")
  val clockIndices  = clocks.zipWithIndex.toMap
  val reasonSources = reason.toSeq.flatMap(record => sources.filterNot(_.name == record.root))
  val reasonWidth   = reasonSources.size.max(1)

  def bindings: ujson.Value = ujson.Obj(
    "sources" -> ujson.Arr.from(
      sources.zipWithIndex.map((source, index) =>
        ujson.Obj("name" -> source.name, "port" -> index, "activeLow" -> source.activeLow)
      )
    ),
    "clocks"  -> ujson.Arr.from(clocks.zipWithIndex.map((name, index) => ujson.Obj("name" -> name, "port" -> index))),
    "targets" -> ujson.Arr.from(
      targets.zipWithIndex.map((target, index) =>
        ujson.Obj("name" -> target.name, "port" -> index, "activeLow" -> target.activeLow)
      )
    ),
    "reasons" -> ujson.Arr.from(
      reasonSources.zipWithIndex.map((source, index) => ujson.Obj("source" -> source.name, "bit" -> index))
    )
  )

given upickle.default.ReadWriter[ResetTreeParameter] = upickle.default.macroRW

class ResetTreeLayers(parameter: ResetTreeParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ResetTreeReasonPort(width: Int) extends Bundle:
  val clear   = Flipped(Bool())
  val reasons = Aligned(Bits(width))
  val valid   = Aligned(Bool())

class ResetTreeIO(parameter: ResetTreeParameter) extends HWBundle(parameter):
  val sources    = Flipped(Vec(parameter.sources.size, Reset()))
  val clocks     = Flipped(Vec(parameter.clocks.size, Clock()))
  val testEnable = Option.when(parameter.processors.nonEmpty)(Flipped(Bool()))
  val outputs    = Aligned(Vec(parameter.targets.size, Reset()))
  val reason     = Option.when(parameter.reason.nonEmpty)(Aligned(new ResetTreeReasonPort(parameter.reasonWidth)))

class ResetTreeProbe(parameter: ResetTreeParameter) extends DVBundle[ResetTreeParameter, ResetTreeLayers](parameter)

@generator
object ResetTree extends Generator[ResetTreeParameter, ResetTreeLayers, ResetTreeIO, ResetTreeProbe]:
  def main(args: Array[String]): Unit = args.toList match
    case "bindings" :: config :: Nil =>
      println(upickle.default.read[ResetTreeParameter](os.read(os.Path(config, os.pwd))).bindings.render())
    case _                           => this.mainImpl(args)

  def architecture(parameter: ResetTreeParameter) =
    val io       = summon[Interface[ResetTreeIO]]
    val asserted = parameter.sources.zipWithIndex.map: (source, index) =>
      source.name -> (if source.activeLow then !io.sources(index).asBool else io.sources(index).asBool)
    val sources  = asserted.toMap

    def process(input: Referable[Bool], processing: Option[ResetProcessing]): Referable[Bool] =
      processing.fold(input): config =>
        val clock      = io.clocks(parameter.clockIndices(config.clockName))
        val testEnable = io.testEnable.get
        def counter(cycles: Int): Referable[Bool] =
          val cell = ResetCounter.instantiate(ResetCounterParameter(cycles))
          cell.io.clock      := clock
          cell.io.resetN     := (!input).asReset
          cell.io.testEnable := testEnable
          !cell.io.output.asBool
        config match
          case ResetProcessing.Async(_, stages)    =>
            if stages == 1 then counter(1)
            else
              val cell = SynchronizedReset.instantiate(SynchronizedResetParameter(stages, RegResetPolarity.NegReset))
              cell.io.clock := clock
              cell.io.reset := (!input).asReset
              testEnable ? (input, !cell.io.synchronizedReset.asBool)
          case ResetProcessing.Pipeline(_, stages) =>
            val cell = ResetPipeline.instantiate(ResetPipelineParameter(stages))
            cell.io.clock      := clock
            cell.io.resetN     := (!input).asReset
            cell.io.testEnable := testEnable
            !cell.io.output.asBool
          case ResetProcessing.Counter(_, cycles)  => counter(cycles)

    parameter.targets.zipWithIndex.foreach: (target, index) =>
      val links    = target.links.map(link => process(sources(link.source), link.processing))
      val combined = links.foldLeft(false.B: Referable[Bool])(_ | _)
      val output   = process(combined, target.processing)
      io.outputs(index) := (if target.activeLow then !output else output).asReset
      if target.links.nonEmpty then
        layer("Verification"):
          locally:
            given ClockEvent = posedge(output.asClock)
            Cover(true.B.S, true.B, s"target_${index}_asserted")
          locally:
            given ClockEvent = negedge(output.asClock)
            Cover(true.B.S, true.B, s"target_${index}_released")
          if target.processing.nonEmpty || target.links.exists(_.processing.nonEmpty) then
            given ClockEvent = posedge(io.testEnable.get.asClock)
            Cover(true.B.S, true.B, s"target_${index}_test_bypass")

    parameter.reason.foreach: config =>
      val cell  = ResetReason.instantiate(ResetReasonParameter(parameter.reasonWidth))
      val ports = io.reason.get
      cell.io.clock                                             := io.clocks(parameter.clockIndices(config.clock))
      cell.io.coldResetN                                        := (!sources(config.root)).asReset
      cell.io.clear                                             := ports.clear
      if parameter.reasonSources.isEmpty then cell.io.events(0) := false.B.asReset
      else
        parameter.reasonSources.zipWithIndex.foreach: (source, index) =>
          cell.io.events(index) := sources(source.name).asReset
      ports.reasons                                             := cell.io.reasons
      ports.valid                                               := cell.io.valid
