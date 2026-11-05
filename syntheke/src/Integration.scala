package me.jiuyang.syntheke

import scala.collection.immutable.VectorMap
import scala.collection.mutable

/** A frozen design as the design instantiating it sees it: a boundary module whose nodes stand for the frozen design's
  * boundaries, and a copy, under the instance, of every frozen domain those boundaries reach. A domain the
  * instantiating design feeds to a boundary takes the place of the frozen domain there.
  */
private[syntheke] final class BoundaryImport(
  val definition: ResolvedDesign,
  placement:      DesignPlacement,
  feeds:          Seq[(Boundary[?], Seq[Domain[?]])],
  val loc: SourceLoc):
  /** Where the instance is. */
  val root: ModuleId = placement.root
  private val id   = root.parent.get / s"$$${root.path.last}"
  private val name = definition.spec.wrapper(ModuleId.root).get.moduleName

  /** The frozen design's boundary each boundary at the instance stands for. */
  private val original: VectorMap[Port[?], Boundary[?]] =
    VectorMap.from(placement.boundaries.map((frozen, placed) => placed.terminal -> frozen))

  def contains(boundary: Boundary[?]): Boolean = original.contains(boundary.terminal)

  /** The frozen edge at `boundary`, a boundary at the instance. */
  def edge(boundary: Boundary[?], at: SourceLoc): ResolvedEdge =
    check(contains(boundary), at)(s"boundary ${boundary.id.show} is not a boundary of $name")
    definition.boundaryEdge(original(boundary.terminal), at)

  private def frozen(boundary: Boundary[?], kind: DomainKind, at: SourceLoc): Domain[?] =
    boundary.terminal.domains
      .find(_.kind eq kind)
      .getOrElse(fail(s"boundary ${boundary.id.show} is in no ${kind.name} domain", at))

  private def kinds(boundary: Boundary[?]): Vector[DomainKind] = boundary.terminal.domains.map(_.kind)

  private val fed: VectorMap[Domain[?], Domain[?]] =
    val pairs = feeds.flatMap { (boundary, domains) =>
      check(contains(boundary), loc)(s"${boundary.id.show} is not a boundary of $name")
      domains.map(domain => frozen(boundary, domain.kind, loc) -> domain)
    }.distinct
    pairs.groupedBy(_._1).foreach { (domain, feeding) =>
      check(feeding.sizeIs == 1, loc)(s"$domain is fed both ${feeding.map(_._2.id.show).mkString(" and ")}")
    }
    pairs.groupedBy(_._2).foreach { (domain, standingFor) =>
      check(standingFor.sizeIs == 1, loc)(
        s"$domain is fed for ${standingFor.map(_._1).mkString(" and ")}, which $name keeps apart"
      )
    }
    pairs.foreach { (frozen, domain) =>
      val kind = domain.kind
      check(kind.describe(domain.asInstanceOf) == kind.describe(frozen.asInstanceOf), loc)(
        s"$domain is fed for $frozen, whose ${kind.name} properties differ"
      )
    }
    VectorMap.from(pairs)

  private val copies = mutable.LinkedHashMap.empty[Domain[?], Domain[?]]

  /** The domain of this design that takes the place of `domain`, a domain of the frozen design. */
  private def standing[K <: DomainKind](domain: Domain[K]): Domain[K] =
    fed
      .get(domain)
      .orElse(copies.get(domain))
      .getOrElse {
        val copy = new Domain(
          domain.kind,
          DomainId(ModuleId(root.path ++ domain.id.module.path), domain.id.name),
          domain.kind.map(domain.definition)([K2 <: DomainKind] => (named: Domain[K2]) => standing(named)),
          loc
        )
        copies(domain) = copy
        copy
      }
      .asInstanceOf[Domain[K]]

  /** The domain of this design that takes the place of the frozen domain `boundary` is in. */
  def domain[K <: DomainKind](boundary: Boundary[?], kind: K, at: SourceLoc): Domain[K] =
    check(contains(boundary), at)(s"boundary ${boundary.id.show} is not a boundary of $name")
    standing(frozen(boundary, kind, at)).asInstanceOf[Domain[K]]

  /** The domains of a node standing for `boundary`. */
  def domains(boundary: Boundary[?]): Vector[Domain[?]] =
    kinds(boundary).map(kind => standing(frozen(boundary, kind, loc)))

  private val entries = placement.boundaries.values.toVector.map { boundary =>
    val outward = boundary.isInstanceOf[OutwardBoundary[?]]
    val edge    = this.edge(boundary, loc)
    val members = domains(boundary)
    val node    = ModuleNodeId(id, boundary.id.name)
    val port: Port[?] =
      if outward then new OutwardPort(boundary.protocol, node, members)
      else new InwardPort(boundary.protocol, node, members)
    val spec = NodeSpec(
      node.name,
      if outward then NodeDirection.Outward else NodeDirection.Inward,
      boundary.protocol,
      NodeComputation.constant(if outward then edge.down else edge.up),
      members,
      loc
    )
    (boundary.terminal, port, spec)
  }

  private val ports: VectorMap[Port[?], Port[?]] = VectorMap.from(entries.map((terminal, port, _) => terminal -> port))

  /** The copies of frozen domains this design holds for the instance. */
  def copiedDomains:               Vector[Domain[?]]  = copies.values.toVector
  val spec:                        BoundaryModuleSpec = BoundaryModuleSpec(id, root, true, entries.map(_._3), loc)
  val probes:                      ProbeCatalog       = placement.probes
  def port(boundary: Boundary[?]): Port[?]            = ports(boundary.terminal)

  /** The instantiating design must keep every boundary's edge. A copy of a frozen domain keeps its properties and stays
    * apart from the others; a fed domain is checked when it is fed.
    */
  def violations(edges: Vector[ResolvedEdge]): Vector[String] =
    placement.boundaries.values.toVector.collect {
      case boundary if {
            val (expected, actual) = (edge(boundary, loc), ResolvedEdge.at(edges, port(boundary).id))
            actual.down != expected.down || actual.up != expected.up || actual.edge != expected.edge ||
            actual.interface != expected.interface
          } =>
        s"the instantiating design changes the frozen contract at ${boundary.id.show}, at ${loc.show}"
    }
