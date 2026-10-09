package me.jiuyang.syntheke

import upickle.default.Writer

/** One kind of domain: clock, reset, power, security, ... The kind defines what a root is, how a domain derives
  * from others, and what a bind crosses between two of its domains. The framework knows no kind by name.
  */
trait DomainKind:
  /** Properties of a domain that derives from nothing. */
  type Root

  /** How a derived domain relates to its sources. */
  type Link

  /** What a bind between members of two domains crosses. */
  type Relation

  def name: String

  /** A member of a physical domain takes it from a carrier input or from a domain its own module declares. */
  def physical: Boolean

  /** A wrapper scope may place every module it creates in a domain of this kind. */
  def scoped: Boolean

  def relate(a: Settled[this.type], b: Settled[this.type]): Relation

  /** The properties that make two domains interchangeable; a frozen boundary compares these. */
  def describe(domain: Settled[this.type]): ujson.Value

  def checks: Seq[DomainCheck] = Seq.empty

  def rootWriter:     Writer[Root]
  def linkWriter:     Writer[Link]
  def relationWriter: Writer[Relation]

/** Where a domain comes from at build time: a declared domain, or whatever domain a node of this module is in. */
sealed trait DomainSource[K <: DomainKind]:
  val kind: K

object DomainSource:
  given [K <: DomainKind]: Writer[DomainSource[K]] = upickle.default.writer[ujson.Value].comap {
    case domain: Domain[?]  => ujson.Obj("domain" -> domain.id.show)
    case ref:    DomainRef[?] => ujson.Obj("node" -> ref.node.show)
  }

/** A domain declared in a module body. */
final class Domain[K <: DomainKind] private[syntheke] (
  val kind:                     K,
  val id:                       DomainId,
  private[syntheke] val origin: Domain.Origin[DomainSource[?]],
  private[syntheke] val loc:    SourceLoc)
    extends DomainSource[K]

object Domain:
  /** A root's properties, the sources and link of a derived domain, or a frozen design's domain. */
  private[syntheke] enum Origin[+S]:
    case Root(value: Any)
    case Derived(sources: Vector[S], link: Any)
    case Imported(domain: Settled[?])

/** The domain of one kind that a node is in, known once binds are resolved. */
final class DomainRef[K <: DomainKind] private[syntheke] (val kind: K, val node: ModuleNodeId) extends DomainSource[K]

/** A domain after negotiation: every source is resolved, so its derivation is plain data. */
final class Settled[K <: DomainKind] private[syntheke] (
  val kind:                     K,
  val id:                       DomainId,
  origin:                       Domain.Origin[Settled[?]],
  private[syntheke] val design: DomainTable):

  def root: Option[kind.Root] = origin match
    case Domain.Origin.Root(value) => Some(value.asInstanceOf[kind.Root])
    case _                         => None

  def sources: Vector[Settled[K]] = origin match
    case Domain.Origin.Derived(sources, _) => sources.asInstanceOf[Vector[Settled[K]]]
    case _                                 => Vector.empty

  def link: Option[kind.Link] = origin match
    case Domain.Origin.Derived(_, link) => Some(link.asInstanceOf[kind.Link])
    case _                              => None

  /** A domain a frozen design declared; this one stands for it in the instantiating design. */
  def imported: Option[Settled[K]] = origin match
    case Domain.Origin.Imported(domain) => Some(domain.asInstanceOf[Settled[K]])
    case _                              => None

  def underlying: Settled[K] = imported.fold(this)(_.underlying)

  /** The settled domain a source in this domain's root or link names, such as the clock a reset is released on. */
  def resolve[K2 <: DomainKind](source: DomainSource[K2]): Settled[K2] = design(source)

  override def toString: String = id.show

/** The graph a design's settled domains belong to. Settlement seals it once, before any check or parameter function
  * reads a domain.
  */
private[syntheke] final class DomainTable:
  private var graph = Option.empty[DomainGraph]

  private[syntheke] def seal(settled: DomainGraph): Unit =
    if graph.isDefined then throw IllegalStateException("domains are settled once")
    graph = Some(settled)

  def apply[K <: DomainKind](source: DomainSource[K]): Settled[K] =
    graph.getOrElse(throw IllegalStateException("domains are read before they are settled"))(source)

/** A protocol's answer to what a bind of it may cross in one domain kind. */
final class Accept private (val kind: DomainKind, private[syntheke] val test: Any => Boolean)

object Accept:
  def apply[K <: DomainKind](kind: K)(test: kind.Relation => Boolean): Accept =
    new Accept(kind, relation => test(relation.asInstanceOf[kind.Relation]))

enum CheckStage derives CanEqual:
  case WellFormed, Behavior

/** A check a domain kind plugs into negotiation. All checks of one stage run; any failure stops before the next. */
trait DomainCheck:
  def name:  String
  def stage: CheckStage
  def run(graph: DomainGraph): Vector[String]

object DomainCheck:
  def apply(name: String, stage: CheckStage)(run: DomainGraph => Vector[String]): DomainCheck =
    val (n, st, r) = (name, stage, run)
    new DomainCheck:
      val name  = n
      val stage = st
      def run(graph: DomainGraph): Vector[String] = r(graph)

/** A design's settled domains and every node's membership. Checks read it; so do generator parameters. */
final class DomainGraph private[syntheke] (
  val domains:                    Vector[Settled[?]],
  declared:                       Map[Domain[?], Settled[?]],
  private[syntheke] val membership: Map[(ModuleNodeId, DomainKind), Settled[?]]):

  private[syntheke] def placed(node: ModuleNodeId => ModuleNodeId): DomainGraph =
    new DomainGraph(domains, declared, membership.map { case ((id, kind), domain) => (node(id), kind) -> domain })

  def apply[K <: DomainKind](source: DomainSource[K]): Settled[K] = source match
    case domain: Domain[?] =>
      declared.getOrElse(domain, fail(s"domain ${domain.id.show} is not declared in this design", domain.loc))
        .asInstanceOf[Settled[K]]
    case ref: DomainRef[?] =>
      member(ref.node, ref.kind).getOrElse(fail(s"node ${ref.node.show} is in no ${ref.kind.name} domain"))

  /** Every kind with a domain or a member in this graph, by name. */
  def kinds: Vector[DomainKind] = (domains.map(_.kind) ++ membership.keys.map(_._2)).distinct.sortBy(_.name)

  def of[K <: DomainKind](kind: K): Vector[Settled[K]] =
    domains.collect { case d if d.kind eq kind => d.asInstanceOf[Settled[K]] }

  def member[K <: DomainKind](node: ModuleNodeId, kind: K): Option[Settled[K]] =
    membership.get(node -> kind).map(_.asInstanceOf[Settled[K]])

  def members[K <: DomainKind](kind: K): Vector[(ModuleNodeId, Settled[K])] =
    membership.toVector.collect { case ((node, k), d) if k eq kind => node -> d.asInstanceOf[Settled[K]] }
      .sortBy(_._1.show)
