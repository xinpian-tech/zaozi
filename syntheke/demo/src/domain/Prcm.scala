package me.jiuyang.syntheke.demo

import me.jiuyang.stdlib.prcm
import me.jiuyang.stdlib.prcm.{PRCMModel, PRCMPolicy, PRCMTarget, given}
import me.jiuyang.syntheke.*
import upickle.default.{writeJs, Writer}

import scala.collection.concurrent.TrieMap

// PRCM domains for zaozi PR #159's PRCM (prcm/PRCM.scala, PRCMChip.scala). A root PRCM domain is one PRCM controller,
// one #159 `PRCMParameter`; each domain it manages derives from it. The types follow #159's field by field, except
// that service providers name domains instead of strings, a managed domain names the power domain it switches, and it
// states its policy in each chip mode, Local unless fixed. The structural rules #159 requires of `PRCMParameter` run
// as well-formedness checks and its model proofs as behavior checks, before any RTL. `parameter` lowers a controller
// to the `PRCMParameter` its generator takes; a managed domain is named there by its declared name.

/** #159 `PRCMService`, naming its provider as a domain. */
final case class PRCMService(name: String, provider: DomainSource[PRCMDomain.type]) derives Writer:
  require(name.nonEmpty, "service names must not be empty")

/** #159 `PRCMMode`. */
final case class PRCMMode(name: String, code: BigInt, target: PRCMTarget, services: Vector[PRCMService] = Vector.empty)
    derives Writer

/** A #159 `PRCMChipMode` without the domains: each managed domain states its own policy in it. */
final case class PRCMChipMode(name: String, code: BigInt) derives Writer

/** #159 `PRCMChip`. */
final case class PRCMChip(modes: Vector[PRCMChipMode], resetMode: String) derives Writer:
  require(modes.nonEmpty, "chip needs at least one mode")
  require(modes.forall(_.name.nonEmpty), "chip mode names must not be empty")
  require(modes.map(_.name).distinct.size == modes.size, "chip mode names must be unique")
  require(modes.forall(_.code >= 0), "chip mode codes must be nonnegative")
  require(modes.map(_.code).distinct.size == modes.size, "chip mode codes must be unique")
  require(modes.exists(_.name == resetMode), s"chip reset mode $resetMode is not declared")

/** One PRCM controller: the parts of #159 `PRCMParameter` that are not its domains, services or register layout. */
final case class PRCMController(coldResetStages: Int, chip: Option[PRCMChip] = None) derives Writer:
  require(coldResetStages > 0, "cold reset stages must be positive")

/** #159 `PRCMManagedDomain` without its name, with the power domain it switches and its chip policies. */
final case class PRCMManagedDomain(
  power:       DomainSource[PowerDomain.type],
  modes:       Vector[PRCMMode],
  resetMode:   String,
  resetStages: Int,
  chip:        Vector[(String, PRCMPolicy)] = Vector.empty)
    derives Writer:
  require(modes.forall(_.name.nonEmpty), "a PRCM domain has an empty mode name")
  require(modes.map(_.name).distinct.size == modes.size, "a PRCM domain has duplicate mode names")
  require(modes.forall(_.code >= 0), "PRCM mode codes must be nonnegative")
  require(modes.map(_.code).distinct.size == modes.size, "a PRCM domain has duplicate mode codes")
  require(modes.exists(_.target == PRCMTarget.Off), "a PRCM domain needs an Off mode for recovery")
  require(modes.exists(_.name == resetMode), s"PRCM reset mode $resetMode is not declared")
  require(resetStages > 0, "PRCM reset stages must be positive")
  require(modes.filter(_.target != PRCMTarget.Run).forall(_.services.isEmpty), "a PRCM domain only uses services in Run")
  require(
    modes.filter(_.target == PRCMTarget.Run).map(_.services.map(_.name).toSet).distinct.size <= 1,
    "the Run modes of a PRCM domain must use the same services"
  )
  require(chip.map(_._1).distinct.size == chip.size, "a PRCM domain states its policy in a chip mode once")
  require(
    chip.forall { case (_, policy) => policy match
        case PRCMPolicy.Fixed(mode) => modes.exists(_.name == mode)
        case PRCMPolicy.Local       => true
    },
    "a fixed chip policy must name a mode of the domain"
  )

  def reset: PRCMMode = modes.find(_.name == resetMode).get

  /** The services the domain needs in Run. */
  def services: Vector[PRCMService] =
    modes.filter(_.target == PRCMTarget.Run).flatMap(_.services).distinctBy(_.name).sortBy(_.name)

  /** The domain's policy in chip mode `mode`. */
  def policy(mode: String): PRCMPolicy = chip.find(_._1 == mode).fold(PRCMPolicy.Local)(_._2)

  /** The target chip mode `mode` fixes, if it fixes one. */
  def fixed(mode: String): Option[PRCMTarget] = policy(mode) match
    case PRCMPolicy.Fixed(name) => modes.find(_.name == name).map(_.target)
    case PRCMPolicy.Local       => None

enum PRCMRelation derives Writer:
  case Same, Different

object PRCMDomain extends DomainKind:
  type Root     = PRCMController
  type Link     = PRCMManagedDomain
  type Relation = PRCMRelation

  val name     = "prcm"
  val physical = false
  val scoped   = false

  /** A domain `controller` manages. */
  def manage(
    controller: DomainSource[PRCMDomain.type]
  )(domain:     PRCMManagedDomain
  )(
    using
    context:    BuildContext[?],
    name:       sourcecode.Name,
    file:       sourcecode.File,
    line:       sourcecode.Line
  ): Domain[PRCMDomain.type] = PRCMDomain.derive(controller)(domain)

  def isController(domain: Settled[PRCMDomain.type]): Boolean = domain.imported.fold(domain.root.isDefined)(isController)

  def managed(domain: Settled[PRCMDomain.type]): PRCMManagedDomain = domain.imported match
    case Some(frozen) => managed(frozen)
    case None         => domain.link.getOrElse(throw IllegalArgumentException(s"$domain is a PRCM controller, not a managed domain"))

  def settings(controller: Settled[PRCMDomain.type]): PRCMController = controller.imported.fold(controller.root.get)(settings)

  /** The controller of a managed domain this design declares. */
  def controller(domain: Settled[PRCMDomain.type]): Settled[PRCMDomain.type] = domain.sources.head

  /** The power domain a managed domain switches. */
  def power(domain: Settled[PRCMDomain.type]): Settled[PowerDomain.type] = domain.imported match
    case Some(frozen) =>
      domain.counterpart(power(frozen)).getOrElse(fail(s"the power domain $domain switches is not at a boundary of its design"))
    case None         => domain.resolve(managed(domain).power)

  private[demo] final case class Edge(consumer: Settled[PRCMDomain.type], provider: Settled[PRCMDomain.type], service: String)

  /** The PRCM domains this design declares; an imported one was checked in its own design. */
  private def all(graph: DomainGraph): Vector[Settled[PRCMDomain.type]] = graph.of(PRCMDomain).filter(_.imported.isEmpty)

  private[demo] def managedDomains(graph: DomainGraph): Vector[Settled[PRCMDomain.type]] = all(graph).filter(_.link.isDefined)

  private[demo] def edges(graph: DomainGraph): Vector[Edge] =
    for
      consumer <- managedDomains(graph)
      service  <- managed(consumer).services
    yield Edge(consumer, consumer.resolve(service.provider), service.name)

  def relate(a: Settled[PRCMDomain.type], b: Settled[PRCMDomain.type]): PRCMRelation =
    if a eq b then PRCMRelation.Same else PRCMRelation.Different

  def describe(domain: Settled[PRCMDomain.type]): ujson.Value = domain.imported match
    case Some(frozen) => describe(frozen)
    case None         => describeDeclared(domain)

  private def describeDeclared(domain: Settled[PRCMDomain.type]): ujson.Value = domain.root match
    case Some(controller) => ujson.Obj("controller" -> writeJs(controller))
    case None             =>
      val d = managed(domain)
      ujson.Obj(
        "power"       -> PowerDomain.describe(power(domain)),
        "modes"       -> d.modes.map(m => ujson.Obj("name" -> m.name, "code" -> m.code.toString, "target" -> m.target.toString)),
        "resetMode"   -> d.resetMode,
        "resetStages" -> d.resetStages,
        "services"    -> d.services.map(_.name)
      )

  // The models do not depend on the design, so each is checked once per process.
  private lazy val sequence = PRCMModel.domain
  private lazy val service  = PRCMModel.service
  private val receivers     = TrieMap.empty[Int, Vector[String]]
  private val prefixes      = TrieMap.empty[Int, Vector[String]]

  override val checks = Seq(
    DomainCheck("controllers", CheckStage.WellFormed) { graph =>
      val parents = managedDomains(graph).collect {
        case d if d.sources.size != 1 || !isController(d.sources.head) =>
          s"$d must derive from exactly one PRCM controller"
      }
      val names   = managedDomains(graph).filter(_.sources.size == 1).groupBy(d => (controller(d), d.id.name)).collect {
        case ((controller, name), domains) if domains.size > 1 =>
          s"$controller manages ${domains.mkString(", ")}, all named $name"
      }
      parents ++ names.toVector.sorted
    },
    DomainCheck("services", CheckStage.WellFormed) { graph =>
      val edges     = this.edges(graph)
      val providers = edges.groupBy(_.service).toVector.sortBy(_._1).collect {
        case (name, uses) if uses.map(_.provider).distinct.size > 1 =>
          s"service $name has providers ${uses.map(_.provider).distinct.mkString(", ")}"
      }
      val local     = edges.collect {
        case e if !(controller(e.provider) eq controller(e.consumer)) =>
          s"${e.consumer} needs ${e.service} from ${e.provider}, which another controller manages"
      }
      val run       = edges.map(_.provider).distinct.collect {
        case provider if !managed(provider).modes.exists(_.target == PRCMTarget.Run) =>
          s"service provider $provider must have a Run mode"
      }
      val consumers = edges.map(_.consumer).toSet
      val both      = edges.map(_.provider).distinct.filter(consumers).map(d => s"$d both provides and consumes a service")
      providers ++ local ++ run ++ both
    },
    DomainCheck("power", CheckStage.WellFormed) { graph =>
      val switched = managedDomains(graph).groupBy(power).toVector.sortBy(_._1.toString)
      val shared   = switched.collect {
        case (power, managers) if managers.size > 1 => s"$power is managed by ${managers.mkString(", ")}"
      }
      val loose    = graph.of(PowerDomain).filter(d => d.imported.isEmpty && !PowerDomain.tree(d).alwaysOn)
        .filterNot(d => switched.exists(_._1 eq d))
        .map(d => s"switched $d is managed by no PRCM domain")
      shared ++ loose
    },
    DomainCheck("chip", CheckStage.WellFormed) { graph =>
      managedDomains(graph).flatMap { domain =>
        val modes = settings(controller(domain)).chip.toVector.flatMap(_.modes.map(_.name))
        managed(domain).chip.map(_._1).filterNot(modes.contains).map(m => s"$domain sets a policy in undeclared chip mode $m")
      } ++ edges(graph).flatMap { e =>
        settings(controller(e.consumer)).chip.toVector.flatMap(_.modes).collect {
          case mode
              if managed(e.provider).fixed(mode.name).exists(_ != PRCMTarget.Run) &&
                !managed(e.consumer).fixed(mode.name).exists(_ != PRCMTarget.Run) =>
            s"chip mode ${mode.name} prevents ${e.provider} from serving ${e.consumer}"
        }
      }
    },
    // #159's own rules on its parameter, on a register port wide enough for any of them.
    DomainCheck("parameter", CheckStage.WellFormed) { graph =>
      all(graph).filter(_.root.isDefined).flatMap { controller =>
        if ports(graph, controller).isEmpty then Vector(s"$controller manages no domain")
        else
          try
            parameter(graph, controller, 32, 32)
            Vector.empty
          catch case e: IllegalArgumentException => Vector(s"$controller: ${e.getMessage}")
      }
    },
    DomainCheck("sequence", CheckStage.Behavior) { graph =>
      if managedDomains(graph).isEmpty then Vector.empty else sequence
    },
    DomainCheck("service", CheckStage.Behavior) { graph =>
      if edges(graph).isEmpty then Vector.empty else service
    },
    DomainCheck("receiver", CheckStage.Behavior) { graph =>
      managedDomains(graph).map(managed(_).resetStages).distinct.sorted
        .flatMap(stages => receivers.getOrElseUpdate(stages, PRCMModel.receiver(stages))) ++
        all(graph).flatMap(_.root).map(_.coldResetStages).distinct.sorted
          .flatMap(stages => prefixes.getOrElseUpdate(stages, PRCMModel.prefix(stages)))
    }
  )

  /** The managed domains of `controller`, in #159's port order: by name. */
  def ports(graph: DomainGraph, controller: Settled[PRCMDomain.type]): Vector[Settled[PRCMDomain.type]] =
    managedDomains(graph).filter(d => this.controller(d) eq controller).sortBy(_.id.name)

  /** The #159 `PRCMParameter` of `controller` behind a register port of the given widths. */
  def parameter(
    graph:      DomainGraph,
    controller: Settled[PRCMDomain.type],
    indexWidth: Int,
    dataWidth:  Int
  ): prcm.PRCMParameter =
    val config  = settings(controller)
    val domains = ports(graph, controller)
    prcm.PRCMParameter(
      indexWidth,
      dataWidth,
      config.coldResetStages,
      domains.map { domain =>
        val d = managed(domain)
        prcm.PRCMManagedDomain(
          domain.id.name,
          d.modes.map(m => prcm.PRCMMode(m.name, m.code, m.target, m.services.map(_.name))),
          d.resetMode,
          d.resetStages
        )
      },
      edges(graph).filter(e => domains.exists(_ eq e.consumer)).distinctBy(_.service).sortBy(_.service)
        .map(e => prcm.PRCMService(e.service, e.provider.id.name)),
      config.chip.map { chip =>
        prcm.PRCMChip(
          chip.modes.map(m =>
            prcm.PRCMChipMode(m.name, m.code, domains.map(d => d.id.name -> managed(d).policy(m.name)).toMap)
          ),
          chip.resetMode
        )
      }
    )

  val rootWriter:     Writer[PRCMController]    = summon
  val linkWriter:     Writer[PRCMManagedDomain] = summon
  val relationWriter: Writer[PRCMRelation]      = summon
