// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.power

import me.jiuyang.stdlib.default.{SynchronizedReset, SynchronizedResetParameter}
import me.jiuyang.stdlib.reset.{ResetCounter, ResetCounterParameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.dialect.firrtl.operation.RegResetPolarity

enum PowerDependencyKind derives upickle.default.ReadWriter:
  case Hard, Soft

case class PowerDependency(source: String, kind: PowerDependencyKind)

given upickle.default.ReadWriter[PowerDependency] = upickle.default.macroRW

case class PowerFollow(name: String, clock: String, stages: Int):
  require(stages >= 1, s"follow reset stages must be positive: $stages")

given upickle.default.ReadWriter[PowerFollow] = upickle.default.macroRW

case class PowerTreeDomain(
  name:         String,
  parameter:    PowerControlParameter,
  dependencies: Seq[PowerDependency] = Seq.empty,
  follows:      Seq[PowerFollow] = Seq.empty)

given upickle.default.ReadWriter[PowerTreeDomain] = upickle.default.macroRW

given mainargs.TokensReader.Simple[PowerTreeDomain]:
  def shortName = "domain"
  def read(strs: Seq[String]): Right[Nothing, PowerTreeDomain] = Right(upickle.default.read[PowerTreeDomain](strs.head))

case class PowerTreeParameter(domains: Seq[PowerTreeDomain]) extends Parameter:
  val names         = domains.map(_.name)
  require(names.forall(_.nonEmpty), "power domain names must not be empty")
  require(names.distinct.size == names.size, "power domain names must be unique")
  val domainIndices = names.zipWithIndex.toMap
  domains.foreach: domain =>
    domain.dependencies.foreach(dependency =>
      require(domainIndices.contains(dependency.source), s"power dependency ${dependency.source} is not declared")
    )
  val follows      = domains.flatMap(_.follows)
  require(
    follows.forall(reset => reset.name.nonEmpty && reset.clock.nonEmpty),
    "follow resets and clocks must be named"
  )
  require(follows.map(_.name).distinct.size == follows.size, "follow reset names must be unique")
  val clocks       = follows.map(_.clock).distinct
  val clockIndices = clocks.zipWithIndex.toMap

  def bindings: ujson.Value = ujson.Obj(
    "clocks"  -> ujson.Arr.from(clocks.zipWithIndex.map((name, index) => ujson.Obj("name" -> name, "port" -> index))),
    "domains" -> ujson.Arr.from(domains.zipWithIndex.map: (domain, index) =>
      ujson.Obj(
        "name"         -> domain.name,
        "port"         -> index,
        "kind"         -> (if !domain.parameter.hasSwitch then "alwaysOn"
                   else if domain.dependencies.isEmpty then "root"
                   else "switched"),
        "dependencies" -> ujson.Arr.from(
          domain.dependencies.map(dependency =>
            ujson.Obj("source" -> dependency.source, "kind" -> dependency.kind.toString)
          )
        ),
        "resets"       -> ujson.Arr.from(
          domain.follows.zipWithIndex.map((reset, port) =>
            ujson.Obj("name" -> reset.name, "port" -> port, "clock" -> reset.clock)
          )
        )
      ))
  )

given upickle.default.ReadWriter[PowerTreeParameter] = upickle.default.macroRW

class PowerTreeLayers(parameter: PowerTreeParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class PowerTreeDomainPort(domain: PowerTreeDomain) extends Bundle:
  val powerGood   = Flipped(Bool())
  // Unswitched domains run automatically.
  val enable      = Option.when(domain.parameter.hasSwitch)(Flipped(Bool()))
  val faultClear  = Option.when(domain.parameter.hasSwitch)(Flipped(Bool()))
  val powerSwitch = Option.when(domain.parameter.hasSwitch)(Aligned(Bool()))
  val clockEnable = Aligned(Bool())
  val ready       = Aligned(Bool())
  val fault       = Aligned(Bool())
  val resets      = Aligned(Vec(domain.follows.size, Reset()))

class PowerTreePorts(parameter: PowerTreeParameter) extends Record:
  parameter.domains.zipWithIndex.foreach((domain, index) => Aligned(s"domain_$index", new PowerTreeDomainPort(domain)))

class PowerTreeIO(parameter: PowerTreeParameter) extends HWBundle(parameter):
  val clock      = Flipped(Clock())
  val resetN     = Flipped(Reset())
  val testEnable = Flipped(Bool())
  val clocks     = Flipped(Vec(parameter.clocks.size, Clock()))
  val domains    = Aligned(new PowerTreePorts(parameter))

class PowerTreeProbe(parameter: PowerTreeParameter) extends DVBundle[PowerTreeParameter, PowerTreeLayers](parameter)

@generator
object PowerTree extends Generator[PowerTreeParameter, PowerTreeLayers, PowerTreeIO, PowerTreeProbe]:
  def main(args: Array[String]): Unit = args.toList match
    case "bindings" :: config :: Nil =>
      println(upickle.default.read[PowerTreeParameter](os.read(os.Path(config, os.pwd))).bindings.render())
    case _                           => this.mainImpl(args)

  def architecture(parameter: PowerTreeParameter) =
    val io    = summon[Interface[PowerTreeIO]]
    val cells = parameter.domains.map: domain =>
      val cell = PowerControl.instantiate(domain.parameter)
      cell
    parameter.domains.zipWithIndex.foreach: (domain, index) =>
      val cell  = cells(index)
      val ports = io.domains.field[PowerTreeDomainPort](s"domain_$index")
      def ready(kind: PowerDependencyKind): Referable[Bool] =
        domain.dependencies
          .filter(_.kind == kind)
          .foldLeft(true.B: Referable[Bool]): (result, dependency) =>
            result & cells(parameter.domainIndices(dependency.source)).io.ready
      cell.io.clock := io.clock
      cell.io.resetN     := io.resetN
      cell.io.testEnable := io.testEnable
      cell.io.enable     := (if domain.parameter.hasSwitch then ports.enable.get else true.B)
      cell.io.faultClear := (if domain.parameter.hasSwitch then ports.faultClear.get else false.B)
      cell.io.powerGood  := ports.powerGood
      cell.io.hardReady  := ready(PowerDependencyKind.Hard)
      cell.io.softReady  := ready(PowerDependencyKind.Soft)
      ports.powerSwitch.foreach(_ := cell.io.powerSwitch)
      ports.clockEnable  := cell.io.clockEnable
      ports.ready        := cell.io.ready
      ports.fault        := cell.io.fault
      domain.follows.zipWithIndex.foreach: (reset, resetIndex) =>
        val clock        = io.clocks(parameter.clockIndices(reset.clock))
        val resetN       = (io.resetN.asBool & cell.io.resetGateN.asBool).asReset
        val synchronized = if reset.stages == 1 then
          val receiver = ResetCounter.instantiate(ResetCounterParameter(1))
          receiver.io.clock      := clock
          receiver.io.resetN     := resetN
          receiver.io.testEnable := false.B
          receiver.io.output
        else
          val receiver =
            SynchronizedReset.instantiate(SynchronizedResetParameter(reset.stages, RegResetPolarity.NegReset))
          receiver.io.clock := clock
          receiver.io.reset := resetN
          receiver.io.synchronizedReset
        val released     = io.testEnable | synchronized.asBool
        ports.resets(resetIndex) := released.asReset
        layer("Verification"):
          given ClockEvent = posedge(clock)
          Cover((released & !io.testEnable).S, io.resetN.asBool, s"domain_${index}_reset_${resetIndex}_released")
          Cover(io.testEnable.S, true.B, s"domain_${index}_reset_${resetIndex}_test_release")
