package me.jiuyang.syntheke

/** A frozen design as the design instantiating it sees it: a boundary module whose nodes stand for the frozen
  * design's boundaries. Each domain those boundaries are in becomes a domain of the instantiating design: the
  * domain an inward carrier boundary receives is whatever is bound to that boundary; any other is imported. A
  * boundary that forwards an inward carrier boundary outward has nothing bound to it yet, so it takes the imported
  * domain.
  */
private[syntheke] final class BoundaryImport(val design: ResolvedDesign, val definition: ResolvedDesign):
  private val id  = design.spec.root.parent.get / s"$$${design.spec.root.path.last}"
  private val loc = design.spec.modules(design.spec.root).loc

  private def frozen(boundary: Boundary[?], kind: DomainKind): Settled[?] = design.boundaryDomain(boundary, kind)
  private def nodeOf(boundary: Boundary[?]): ModuleNodeId                  = ModuleNodeId(id, boundary.id.name)

  private val received: Map[Settled[?], DomainRef[?]] = design.spec.boundaries.collect { case b: InwardBoundary[?] =>
    b.protocol.carries.toVector.map(kind => frozen(b, kind) -> new DomainRef(kind, nodeOf(b)))
  }.flatten.reverse.toMap

  private val originals = design.spec.boundaries.flatMap(b => b.terminal.kinds.map(frozen(b, _))).distinct

  val domains: Vector[Domain[?]] = originals.zipWithIndex.map { (domain, index) =>
    new Domain(domain.kind, DomainId(id, s"domain$index"), Domain.Origin.Imported(domain, frozen => imported.get(frozen)), loc)
  }

  private lazy val declared: Map[Settled[?], Domain[?]]        = originals.zip(domains).toMap
  private lazy val imported: Map[Settled[?], DomainSource[?]] = declared ++ received

  def domain[K <: DomainKind](boundary: Boundary[?], kind: K, at: SourceLoc): Domain[K] =
    imported(frozen(boundary, kind)) match
      case domain: Domain[?] => domain.asInstanceOf[Domain[K]]
      case _                 =>
        fail(s"boundary ${boundary.id.show} is in whatever ${kind.name} domain the instantiating design binds to it", at)

  /** Memberships of a node standing for `boundary`: an inward carrier receives what is bound to it; every other
    * kind is the domain the frozen design put the boundary in.
    */
  def memberships(boundary: Boundary[?], direction: NodeDirection, at: SourceLoc)
    : Vector[Membership] =
    boundary.terminal.kinds.map { kind =>
      if direction == NodeDirection.Inward && boundary.protocol.carries(kind) then Membership(kind, None, at)
      else Membership(kind, Some(imported(frozen(boundary, kind))), at)
    }

  /** Memberships of a boundary that forwards inward `boundary`: the frozen design's own domains. */
  def forwarded(boundary: Boundary[?], at: SourceLoc): Vector[Membership] =
    boundary.terminal.kinds.map(kind => Membership(kind, Some(declared(frozen(boundary, kind))), at))

  private val entries = design.spec.boundaries.zipWithIndex.map { (boundary, order) =>
    val outward   = boundary.isInstanceOf[OutwardBoundary[?]]
    val direction = if outward then NodeDirection.Outward else NodeDirection.Inward
    val edge      = design.boundaryEdge(boundary)
    val members   = memberships(boundary, direction, loc)
    val node      = nodeOf(boundary)
    val port: Port[?] =
      if outward then new OutwardPort(boundary.protocol, node, members.map(_.kind))
      else new InwardPort(boundary.protocol, node, members.map(_.kind))
    val spec      = NodeSpec(
      node.name,
      direction,
      boundary.protocol,
      NodeComputation.Constant(if outward then edge.down else edge.up),
      members,
      order,
      loc
    )
    (boundary.terminal, port, spec)
  }

  val ports: Map[Port[?], Port[?]] = entries.map((terminal, port, _) => terminal -> port).toMap
  val spec: BoundaryModuleSpec     = BoundaryModuleSpec(id, design.spec.root, true, entries.map(_._3), loc)
  def contains(boundary: Boundary[?]): Boolean = ports.contains(boundary.terminal)
  def port(boundary: Boundary[?]): Port[?]     = ports(boundary.terminal)

  /** The instantiating design must keep every boundary's edge, the properties of every boundary domain, and which
    * boundary domains are the same.
    */
  def violations(parent: Negotiated): Vector[String] =
    val pairs    = for
      boundary <- design.spec.boundaries
      kind     <- boundary.terminal.kinds
    yield (boundary, kind, frozen(boundary, kind), parent.domains.member(port(boundary).id, kind).get)
    val changed  = pairs.collect {
      case (boundary, kind, expected, actual) if kind.describe(actual.asInstanceOf) != kind.describe(expected.asInstanceOf) =>
        s"the ${kind.name} domain of ${boundary.id.show} was $expected when frozen and is $actual here, at ${loc.show}"
    }
    val same     = pairs.map((_, _, expected, actual) => expected -> actual).distinct
    val regroup  = Option.when(
      same.groupMap(_._1)(_._2).values.exists(_.size > 1) || same.groupMap(_._2)(_._1).values.exists(_.size > 1)
    )(s"the instantiating design changes which domains of ${design.spec.root.show} are the same, at ${loc.show}")
    val contract = design.spec.boundaries.collect {
      case boundary if {
            val (expected, actual) = (design.boundaryEdge(boundary), parent.edgeAt(port(boundary).id))
            actual.down != expected.down || actual.up != expected.up || actual.edge != expected.edge ||
            actual.interface != expected.interface
          } =>
        s"the instantiating design changes the frozen contract at ${boundary.id.show}, at ${loc.show}"
    }
    changed ++ regroup ++ contract

/** Joins a design with the frozen designs it instantiates and plans the ports and wires of the whole. */
private[syntheke] object DesignIntegration:
  def combine(imports: Vector[BoundaryImport], parent: Negotiated, probes: ProbeCatalog): ResolvedDesign =
    val children = imports.map(_.design)
    val specs    = children.map(_.spec) :+ parent.spec
    val modules  = specs.flatMap(_.moduleOrder)
    val spec     = parent.spec.copy(
      modules = specs.flatMap(_.modules).toMap,
      moduleOrder = Vector(parent.spec.root) ++ modules.filterNot(_ == parent.spec.root),
      binds = specs.flatMap(_.binds)
    )
    val names   = spec.generators.groupBy(_.name).toVector.sortBy(_._1).collect {
      case (name, definitions) if definitions.sizeIs > 1 => s"distinct generators share the module name '$name'"
    }
    val wrappers = spec.moduleOrder.flatMap(spec.wrapper).groupBy(_.moduleName).toVector.sortBy(_._1).collect {
      case (name, ws) if ws.map(_.definition).distinct.sizeIs > 1 =>
        s"distinct wrappers share the module name '$name', at ${ws.map(_.loc.show).distinct.mkString(", ")}"
    }
    report("integration", names ++ wrappers)

    val edges                           = children.flatMap(_.edges) ++ parent.edges
    val observations                    = children.flatMap(_.observations).toMap ++ parent.observations
    val (ports, wires, plannedLayers)   = Planner.plan(spec, edges, probes, observations)
    val frozen                          = children.flatMap(_.spec.moduleOrder).toSet
    val (frozenPorts, frozenWires)      = (children.flatMap(_.portPlans), children.flatMap(_.wirePlans))
    if !ports.filter(p => frozen(p.module)).forall(frozenPorts.contains) ||
      !wires.filter(w => frozen(w.module)).forall(frozenWires.contains)
    then throw IllegalStateException("planning changed the ports or wires of a frozen design")
    val layers = children.flatMap(_.layerDecls).foldLeft(plannedLayers) { case (acc, (module, tree)) =>
      acc.updated(module, acc.getOrElse(module, LayerTree.empty).merge(tree))
    }
    ResolvedDesign(
      spec = spec,
      domains = parent.domains,
      edges = edges,
      generatorModules = children.flatMap(_.generatorModules) ++ parent.generatorModules,
      portPlans = (frozenPorts ++ ports).distinct,
      wirePlans = (frozenWires ++ wires).distinct,
      layerDecls = layers,
      probes = probes,
      observations = observations,
      dependencies = imports.map(i => i.design.spec.root -> i.definition)
    )
