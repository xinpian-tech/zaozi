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

/** A domain kind whose domains configure generators: from the settled graph it plans their parameters, for example
  * the clock trees a design needs.
  */
trait Planned extends DomainKind:
  type Plan
  def plan(graph: DomainGraph): Plan
  def planWriter: Writer[Plan]

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
  val kind:                    K,
  val id:                      DomainId,
  private[syntheke] val origin: Domain.Origin,
  private[syntheke] val loc:    (sourcecode.File, sourcecode.Line))
    extends DomainSource[K]

object Domain:
  private[syntheke] enum Origin:
    case Root(value: Any)
    case Derived(sources: Vector[DomainSource[?]], link: Any)
    case Imported(domain: Settled[?])

/** The domain of one kind that a node is in, known once binds are resolved. */
final class DomainRef[K <: DomainKind] private[syntheke] (val kind: K, val node: ModuleNodeId) extends DomainSource[K]

/** A domain after negotiation: every source is resolved, so its derivation is plain data. */
final class Settled[K <: DomainKind] private[syntheke] (
  val kind:   K,
  val id:     DomainId,
  origin:     Settled.Origin,
  resolver:   DomainSource[?] => Settled[?]):

  def root: Option[kind.Root] = origin match
    case Settled.Origin.Root(value) => Some(value.asInstanceOf[kind.Root])
    case _                          => None

  def sources: Vector[Settled[K]] = origin match
    case Settled.Origin.Derived(sources, _) => sources.asInstanceOf[Vector[Settled[K]]]
    case _                                  => Vector.empty

  def link: Option[kind.Link] = origin match
    case Settled.Origin.Derived(_, link) => Some(link.asInstanceOf[kind.Link])
    case _                               => None

  /** A domain a frozen design declared; this one stands for it in the instantiating design. */
  def imported: Option[Settled[K]] = origin match
    case Settled.Origin.Imported(domain) => Some(domain.asInstanceOf[Settled[K]])
    case _                               => None

  def underlying: Settled[K] = imported.fold(this)(_.underlying)

  /** Resolves a source mentioned in this domain's root or link, such as the clock a reset is synchronized to. */
  def resolve[K2 <: DomainKind](source: DomainSource[K2]): Settled[K2] = resolver(source).asInstanceOf[Settled[K2]]

  override def toString: String = id.show

object Settled:
  private[syntheke] enum Origin:
    case Root(value: Any)
    case Derived(sources: Vector[Settled[?]], link: Any)
    case Imported(domain: Settled[?])

/** A protocol's answer to what a bind of it may cross in one domain kind. */
final class Accept private (val kind: DomainKind, private[syntheke] val test: Any => Boolean)

object Accept:
  def apply[K <: DomainKind](kind: K)(test: kind.Relation => Boolean): Accept =
    new Accept(kind, relation => test(relation.asInstanceOf[kind.Relation]))

  /** The protocol places no condition on this kind. */
  def any(kind: DomainKind): Accept = new Accept(kind, _ => true)

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
    case domain: Domain[?]  =>
      declared.getOrElse(domain, throw IllegalArgumentException(s"domain ${domain.id.show} is not of this design"))
        .asInstanceOf[Settled[K]]
    case ref: DomainRef[?] =>
      member(ref.node, ref.kind)
        .getOrElse(throw IllegalArgumentException(s"node ${ref.node.show} is in no ${ref.kind.name} domain"))
        .asInstanceOf[Settled[K]]

  /** Every kind with a domain or a member in this graph, by name. */
  def kinds: Vector[DomainKind] = (domains.map(_.kind) ++ membership.keys.map(_._2)).distinct.sortBy(_.name)

  def of[K <: DomainKind](kind: K): Vector[Settled[K]] =
    domains.collect { case d if d.kind eq kind => d.asInstanceOf[Settled[K]] }

  def member[K <: DomainKind](node: ModuleNodeId, kind: K): Option[Settled[K]] =
    membership.get(node -> kind).map(_.asInstanceOf[Settled[K]])

  def members[K <: DomainKind](kind: K): Vector[(ModuleNodeId, Settled[K])] =
    membership.toVector.collect { case ((node, k), d) if k eq kind => node -> d.asInstanceOf[Settled[K]] }
      .sortBy(_._1.show)

private[syntheke] trait ReadToken:
  type Value

private[syntheke] final class ReadValues(values: Map[ReadToken, Any]):
  private[syntheke] def lookup[T <: ReadToken](token: T): token.Value =
    values
      .getOrElse(token, throw new IllegalArgumentException("token is not present in this sealed read plan"))
      .asInstanceOf[token.Value]
