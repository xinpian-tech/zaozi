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
  def layers = Seq(Layer("Verification"), Layer("Progress"))

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
      layer("Verification"):
        given ClockEvent = posedge(io.clock)
        val active       = io.resetN.asBool & !io.testEnable
        val hard         = ready(PowerDependencyKind.Hard)
        val soft         = ready(PowerDependencyKind.Soft)
        Assert((cell.io.hardReady === hard).S, active, s"domain_${index}_hard_dependencies_connected")
        Assert((cell.io.softReady === soft).S, active, s"domain_${index}_soft_dependencies_connected")
        domain.dependencies.zipWithIndex.foreach: (dependency, dependencyIndex) =>
          val provider = cells(parameter.domainIndices(dependency.source))
          Cover(
            (!provider.io.ready & cell.io.enable).S.##*(provider.io.ready.S).##*(ports.ready.S),
            active,
            s"domain_${index}_dependency_${dependencyIndex}_startup"
          )
        Cover((cell.io.enable & !ports.ready).S.##*(ports.fault.S), active, s"domain_${index}_startup_fault")
        Cover(ports.fault.S.##*(ports.ready.S), active, s"domain_${index}_retry_ready")
      layer("Progress"):
        given ClockEvent = posedge(io.clock)
        val active       = io.resetN.asBool & !io.testEnable
        Assert(
          always((cell.io.enable & !cell.io.faultClear).S) implies eventually((ports.ready | ports.fault).S),
          active,
          s"domain_${index}_dependency_wait_resolves"
        )
        Assert(
          always((!cell.io.enable & !ports.powerGood).S) implies
            eventually((!ports.ready & !ports.clockEnable).S),
          active,
          s"domain_${index}_shutdown_stops_domain"
        )
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

          Assert(
            (io.testEnable | resetN.asBool | !released).I,
            s"domain_${index}_reset_${resetIndex}_asserts_with_power_reset"
          )
          Assert((!io.testEnable | released).I, s"domain_${index}_reset_${resetIndex}_test_bypass")
          Cover(
            (cell.io.ready & !released).S.##*(released.S),
            io.resetN.asBool & !io.testEnable,
            s"domain_${index}_reset_${resetIndex}_startup_complete"
          )
          Cover(
            released.S.##*((!cell.io.ready & !released).S),
            io.resetN.asBool & !io.testEnable,
            s"domain_${index}_reset_${resetIndex}_shutdown_complete"
          )
        layer("Progress"):
          given ClockEvent = posedge(clock)
          Assert(
            always((resetN.asBool & !io.testEnable).S) implies eventually(released.S),
            true.B,
            s"domain_${index}_reset_${resetIndex}_eventually_released"
          )
    if parameter.domains.nonEmpty then
      layer("Progress"):
        given ClockEvent           = posedge(io.clock)
        val requested              = cells.map(cell => cell.io.enable & !cell.io.faultClear).reduce(_ & _)
        val resolved               = cells.map(cell => cell.io.ready | cell.io.fault).reduce(_ & _)
        Assert(
          always(requested.S) implies eventually(resolved.S),
          io.resetN.asBool & !io.testEnable,
          "power_tree_all_requests_resolve"
        )
        // Hard-dependency cycles cannot promise startup. Emit successful-start
        // obligations for every domain whose hard dependencies have a root.
        // Soft dependencies may time out and therefore do not block this ordering.
        val startable              = parameter.domains.indices.foldLeft(Set.empty[Int]): (known, _) =>
          known ++ parameter.domains.indices.filter: index =>
            parameter
              .domains(index)
              .dependencies
              .filter(_.kind == PowerDependencyKind.Hard)
              .forall(dependency => known(parameter.domainIndices(dependency.source)))
        val externalPowerAvailable = cells.map(cell => cell.io.enable & cell.io.powerGood).reduce(_ & _)
        startable.toSeq.sorted.foreach: index =>
          Assert(
            always(externalPowerAvailable.S) implies eventually(cells(index).io.ready.S),
            io.resetN.asBool & !io.testEnable,
            s"domain_${index}_rooted_dependencies_eventually_ready"
          )
        if startable.size == parameter.domains.size then
          Assert(
            always(externalPowerAvailable.S) implies
              eventually(cells.map(_.io.ready: Referable[Bool]).reduce(_ & _).S),
            io.resetN.asBool & !io.testEnable,
            "power_tree_available_power_eventually_ready"
          )
        Cover(
          requested.S.##*(cells.map(_.io.ready: Referable[Bool]).reduce(_ & _).S),
          io.resetN.asBool & !io.testEnable,
          "power_tree_all_domains_ready"
        )
