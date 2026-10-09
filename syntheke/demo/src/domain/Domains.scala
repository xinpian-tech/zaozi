package me.jiuyang.syntheke.demo

import me.jiuyang.stdlib.power.{PowerControlParameter, given}
import me.jiuyang.syntheke.*
import upickle.default.Writer

// Clock, reset and power domains mirroring zaozi PR #159 field by field: ClockTree (clock/ClockTree.scala),
// ResetTree (reset/ResetTree.scala) and PowerTree (power/PowerTree.scala). A tree's named inputs and targets are
// domains here, its string references are typed domain sources, and the cell library stays with the tree generator.
// Once #159 lands these types are replaced by its own.

// ---------------------------------------------------------------- clock

/** A clock tree input. #159 names inputs only; the frequency is what the SoC knows about the input. */
final case class ClockInput(hz: Int) derives Writer:
  require(hz > 0, s"clock frequency $hz Hz must be positive")

/** #159 `ClockGateParameter` without the cell library. */
final case class ClockGate(positive: Boolean, clockDuringReset: Boolean) derives Writer

/** #159 `ClockDividerParameter` without the cell library. */
final case class ClockDivider(width: Int, initial: BigInt, clockDuringReset: Boolean, automatic: Boolean) derives Writer:
  require(width > 0, "divider width must be positive")
  require(initial >= 0 && initial.bitLength <= width, "initial divisor must fit the divider width")
  def initialDivisor: BigInt = initial.max(1)

/** #159 `ClockGuideParameter`. */
final case class ClockGuide(
  cell:     Option[String] = None,
  input:    String = "a",
  output:   String = "outClock",
  instance: Option[String] = None)
    derives Writer:
  require(cell.forall(_.nonEmpty) && Seq(input, output).forall(_.nonEmpty), "clock guide cell and pins must not be empty")
  require(cell.nonEmpty || (input == "a" && output == "outClock"), "clock guide pins need a cell")
  require(input != output, "clock guide pins must be distinct")
  require(instance.forall(_.nonEmpty), "clock guide instance must not be empty")

/** #159 `ClockPath`. */
final case class ClockPath(
  gate:          Option[ClockGate] = None,
  divider:       Option[ClockDivider] = None,
  invert:        Boolean = false,
  gateGuide:     Option[ClockGuide] = None,
  dividerGuide:  Option[ClockGuide] = None,
  inverterGuide: Option[ClockGuide] = None)
    derives Writer:
  require(gateGuide.isEmpty || gate.nonEmpty, "a gate guide needs a gate")
  require(dividerGuide.isEmpty || divider.nonEmpty, "a divider guide needs a divider")
  require(inverterGuide.isEmpty || invert, "an inverter guide needs an inverter")
  def divisor: BigInt = divider.fold(BigInt(1))(_.initialDivisor)

/** #159 `ClockSelection`. */
enum ClockSelection derives Writer:
  case Raw
  case GlitchFree(stages: Int, clockDuringReset: Boolean)

/** #159 `ClockTarget` without its name: `links(i)` is the path from the target's `i`-th source. */
final case class ClockTarget(
  links:     Vector[ClockPath],
  selection: Option[ClockSelection] = None,
  path:      ClockPath = ClockPath(),
  muxGuide:  Option[ClockGuide] = None)
    derives Writer:
  require(links.nonEmpty, "a clock target needs a source")
  require(selection.isDefined == (links.size > 1), "a clock target needs selection exactly when it has multiple links")
  require(muxGuide.isEmpty || selection.nonEmpty, "a clock target mux guide needs a mux")

enum ClockRelation derives Writer:
  case Same

  /** One root drives both clocks; the source frequency over the target frequency is `numerator / denominator`. */
  case Synchronous(numerator: BigInt, denominator: BigInt)
  case Asynchronous

object ClockDomain extends DomainKind:
  type Root     = ClockInput
  type Link     = ClockTarget
  type Relation = ClockRelation

  val name     = "clock"
  val physical = true
  val scoped   = false

  /** A clock tree target over `links`, each a source with its path. */
  def target(
    links:     (DomainSource[ClockDomain.type], ClockPath)*
  )(selection: Option[ClockSelection] = None,
    path:      ClockPath = ClockPath()
  )(
    using
    context:   BuildContext[?],
    name:      sourcecode.Name,
    file:      sourcecode.File,
    line:      sourcecode.Line
  ): Domain[ClockDomain.type] =
    ClockDomain.derive(links.map(_._1)*)(ClockTarget(links.map(_._2).toVector, selection, path))

  /** The root a clock comes from and the division along the way. A mux follows its first link, the one it selects
    * out of reset.
    */
  private def trace(domain: Settled[ClockDomain.type]): (Settled[ClockDomain.type], BigInt) =
    val clock = domain.underlying
    clock.link match
      case None         => clock -> BigInt(1)
      case Some(target) =>
        val (root, divisor) = trace(clock.sources.head)
        root -> divisor * target.links.head.divisor * target.path.divisor

  def hz(domain: Settled[ClockDomain.type]): Int =
    val (root, divisor) = trace(domain)
    (BigInt(root.root.get.hz) / divisor).toInt

  private def muxed(domain: Settled[ClockDomain.type]): Boolean =
    val clock = domain.underlying
    clock.link.exists(_.selection.isDefined) || clock.sources.exists(muxed)

  def relate(a: Settled[ClockDomain.type], b: Settled[ClockDomain.type]): ClockRelation =
    val (rootA, divisorA) = trace(a)
    val (rootB, divisorB) = trace(b)
    if a.underlying eq b.underlying then ClockRelation.Same
    else if (rootA eq rootB) && !muxed(a) && !muxed(b) then ClockRelation.Synchronous(divisorB, divisorA)
    else ClockRelation.Asynchronous

  def describe(domain: Settled[ClockDomain.type]): ujson.Value = ujson.Obj("hz" -> hz(domain))

  override val checks = Seq(
    new DomainCheck:
      val name  = "links"
      val stage = CheckStage.WellFormed
      def run(graph: DomainGraph): Vector[String] =
        graph.of(ClockDomain).collect {
          case clock if clock.link.exists(_.links.size != clock.sources.size) =>
            s"$clock has ${clock.sources.size} sources but ${clock.link.get.links.size} links"
        }
  )

  val rootWriter:     Writer[ClockInput]    = summon
  val linkWriter:     Writer[ClockTarget]   = summon
  val relationWriter: Writer[ClockRelation] = summon

// ---------------------------------------------------------------- reset

/** #159 `ResetProcessing`, naming its clock as a domain. */
enum ResetProcessing derives Writer:
  case Async(clock: DomainSource[ClockDomain.type], stages: Int)
  case Pipeline(clock: DomainSource[ClockDomain.type], stages: Int)
  case Counter(clock: DomainSource[ClockDomain.type], cycles: Int)

  def clockSource: DomainSource[ClockDomain.type] = this match
    case Async(clock, _)    => clock
    case Pipeline(clock, _) => clock
    case Counter(clock, _)  => clock

/** A reset that derives from no other reset: a #159 `ResetSource`; a #159 `PowerFollow` reset that a power domain
  * releases on a clock once it is ready; or the `domainResetN` a #159 PRCM drives for a domain it manages, released
  * on that domain's clock after the domain's reset stages.
  */
enum ResetRoot derives Writer:
  case Source(activeLow: Boolean)
  case PowerFollow(power: DomainSource[PowerDomain.type], clock: DomainSource[ClockDomain.type], stages: Int)
  case Managed(prcm: DomainSource[PRCMDomain.type], clock: DomainSource[ClockDomain.type])

/** #159 `ResetTarget` without its name: `links(i)` processes the target's `i`-th source. */
final case class ResetTarget(
  activeLow:  Boolean,
  links:      Vector[Option[ResetProcessing]],
  processing: Option[ResetProcessing] = None)
    derives Writer

enum ResetRelation derives Writer:
  case Same
  case Different(sameLevel: Boolean)

object ResetDomain extends DomainKind:
  type Root     = ResetRoot
  type Link     = ResetTarget
  type Relation = ResetRelation

  val name     = "reset"
  val physical = true
  val scoped   = false

  /** A reset tree target over `links`, each a source with its processing. */
  def target(
    activeLow:  Boolean,
    links:      (DomainSource[ResetDomain.type], Option[ResetProcessing])*
  )(processing: Option[ResetProcessing] = None
  )(
    using
    context:    BuildContext[?],
    name:       sourcecode.Name,
    file:       sourcecode.File,
    line:       sourcecode.Line
  ): Domain[ResetDomain.type] =
    ResetDomain.derive(links.map(_._1)*)(ResetTarget(activeLow, links.map(_._2).toVector, processing))

  def activeLow(domain: Settled[ResetDomain.type]): Boolean =
    val reset = domain.underlying
    reset.link.map(_.activeLow).getOrElse(reset.root.get match
      case ResetRoot.Source(activeLow)  => activeLow
      case _: ResetRoot.PowerFollow     => true
      case _: ResetRoot.Managed         => true)

  /** The clock a reset is released on, when it is released synchronously. */
  def releaseClock(domain: Settled[ResetDomain.type]): Option[Settled[ClockDomain.type]] =
    val reset = domain.underlying
    (reset.root, reset.link) match
      case (Some(ResetRoot.PowerFollow(_, clock, _)), _) => Some(reset.resolve(clock))
      case (Some(ResetRoot.Managed(_, clock)), _)        => Some(reset.resolve(clock))
      case (_, Some(target)) =>
        target.processing.map(p => reset.resolve(p.clockSource)).orElse {
          val released = target.links.zip(reset.sources).map {
            case (Some(processing), _) => Some(reset.resolve(processing.clockSource))
            case (None, source)        => releaseClock(source)
          }
          released.distinctBy(_.map(_.underlying)) match
            case Vector(clock) => clock
            case _             => None
        }
      case _ => None

  def relate(a: Settled[ResetDomain.type], b: Settled[ResetDomain.type]): ResetRelation =
    if a.underlying eq b.underlying then ResetRelation.Same
    else ResetRelation.Different(activeLow(a) == activeLow(b))

  def describe(domain: Settled[ResetDomain.type]): ujson.Value =
    ujson.Obj(
      "activeLow"    -> activeLow(domain),
      "releaseClock" -> releaseClock(domain).fold(ujson.Null)(ClockDomain.describe)
    )

  override val checks = Seq(
    DomainCheck("managed", CheckStage.WellFormed) { graph =>
      graph.of(ResetDomain).collect {
        case reset if reset.root.exists {
              case ResetRoot.Managed(prcm, _) => reset.resolve(prcm).underlying.link.isEmpty
              case _                          => false
            } =>
          s"$reset is driven by a PRCM controller, not by a domain it manages"
      }
    },
    new DomainCheck:
      val name  = "links"
      val stage = CheckStage.WellFormed
      def run(graph: DomainGraph): Vector[String] =
        graph.of(ResetDomain).collect {
          case reset if reset.link.exists(_.links.size != reset.sources.size) =>
            s"$reset has ${reset.sources.size} sources but ${reset.link.get.links.size} links"
        },
    new DomainCheck:
      val name  = "release clock"
      val stage = CheckStage.WellFormed
      def run(graph: DomainGraph): Vector[String] =
        for
          (node, reset) <- graph.members(ResetDomain)
          released      <- releaseClock(reset)
          clock         <- graph.member(node, ClockDomain)
          relation       = ClockDomain.relate(clock, released)
          if relation != ClockRelation.Same && relation != ClockRelation.Synchronous(1, 1)
        yield s"${node.show} runs on $clock but its reset $reset is released on $released"
  )

  val rootWriter:     Writer[ResetRoot]     = summon
  val linkWriter:     Writer[ResetTarget]   = summon
  val relationWriter: Writer[ResetRelation] = summon

// ---------------------------------------------------------------- power

/** #159 `PowerDependencyKind`. */
enum PowerDependencyKind derives Writer:
  case Hard, Soft

/** #159 `PowerDependency`, naming its source as a domain. */
final case class PowerDependency(source: DomainSource[PowerDomain.type], kind: PowerDependencyKind) derives Writer

/** #159 `PowerTreeDomain` without its name; its follow resets are `ResetRoot.PowerFollow` reset domains. */
final case class PowerTreeDomain(control: PowerControlParameter, dependencies: Vector[PowerDependency] = Vector.empty)
    derives Writer:
  def alwaysOn: Boolean = !control.hasSwitch

enum PowerRelation derives Writer:
  case Same
  case Different(fromAlwaysOn: Boolean, toAlwaysOn: Boolean)

object PowerDomain extends DomainKind:
  type Root     = PowerTreeDomain
  type Link     = Nothing
  type Relation = PowerRelation

  val name     = "power"
  val physical = false
  val scoped   = true

  def tree(domain: Settled[PowerDomain.type]): PowerTreeDomain = domain.underlying.root.get

  def relate(a: Settled[PowerDomain.type], b: Settled[PowerDomain.type]): PowerRelation =
    if a.underlying eq b.underlying then PowerRelation.Same
    else PowerRelation.Different(tree(a).alwaysOn, tree(b).alwaysOn)

  def describe(domain: Settled[PowerDomain.type]): ujson.Value = upickle.default.writeJs(tree(domain).control)

  /** A pin may cross between two always-on supplies, such as the board's and the chip's. */
  def atPin(relation: PowerRelation): Boolean = relation match
    case PowerRelation.Same                         => true
    case PowerRelation.Different(fromOn, toOn)      => fromOn && toOn

  override val checks = Seq(
    new DomainCheck:
      val name  = "dependencies"
      val stage = CheckStage.WellFormed
      def run(graph: DomainGraph): Vector[String] =
        val domains = graph.of(PowerDomain)
        def depends(d: Settled[PowerDomain.type]): Vector[Settled[PowerDomain.type]] =
          tree(d).dependencies.map(dep => d.underlying.resolve(dep.source).underlying)
        def cyclic(start: Settled[PowerDomain.type]): Boolean =
          @annotation.tailrec
          def visit(frontier: List[Settled[PowerDomain.type]], seen: Set[Settled[PowerDomain.type]]): Boolean =
            frontier match
              case Nil                       => false
              case head :: _ if head eq start => true
              case head :: rest              =>
                if seen(head) then visit(rest, seen) else visit(depends(head).toList ++ rest, seen + head)
          visit(depends(start).toList, Set.empty)
        domains.filter(d => d.imported.isEmpty && cyclic(d)).map(d => s"$d depends on itself")
  )

  val rootWriter:     Writer[PowerTreeDomain] = summon
  val linkWriter:     Writer[Nothing]         = upickle.default.writer[Unit].comap(_ => ())
  val relationWriter: Writer[PowerRelation]   = summon
