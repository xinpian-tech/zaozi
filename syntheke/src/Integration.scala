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
    new Domain(domain.kind, DomainId(id, s"domain$index"), Domain.Origin.Imported(domain), loc)
  }

  private val declared: Map[Settled[?], Domain[?]]        = originals.zip(domains).toMap
  private val imported: Map[Settled[?], DomainSource[?]] = declared ++ received

  def domain[K <: DomainKind](boundary: Boundary[?], kind: K): Domain[K] =
    imported(frozen(boundary, kind)) match
      case domain: Domain[?] => domain.asInstanceOf[Domain[K]]
      case _                 => throw IllegalArgumentException(
          s"boundary ${boundary.id.show} is in the ${kind.name} domain the instantiating design binds to it"
        )

  /** Memberships of a node standing for `boundary`: an inward carrier receives what is bound to it; every other
    * kind is the domain the frozen design put the boundary in.
    */
  def memberships(boundary: Boundary[?], direction: NodeDirection, at: (sourcecode.File, sourcecode.Line))
    : Vector[Membership] =
    boundary.terminal.kinds.map { kind =>
      if direction == NodeDirection.Inward && boundary.protocol.carries(kind) then Membership(kind, None, at)
      else Membership(kind, Some(imported(frozen(boundary, kind))), at)
    }

  /** Memberships of a boundary that forwards inward `boundary`: the frozen design's own domains. */
  def forwarded(boundary: Boundary[?]): Vector[Membership] =
    boundary.terminal.kinds.map(kind => Membership(kind, Some(declared(frozen(boundary, kind))), loc))

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
  def validate(parent: ResolvedDesign): Unit =
    val pairs = for
      boundary <- design.spec.boundaries
      kind     <- boundary.terminal.kinds
    yield
      val expected = frozen(boundary, kind)
      val actual   = parent.domains.member(port(boundary).id, kind).get
      require(
        kind.describe(actual.asInstanceOf) == kind.describe(expected.asInstanceOf),
        s"integration changes the frozen ${kind.name} domain at ${boundary.id.show}: $expected became $actual"
      )
      expected -> actual.underlying
    val distinct = pairs.distinct
    require(
      distinct.groupMap(_._1)(_._2).values.forall(_.size == 1) && distinct.groupMap(_._2)(_._1).values.forall(_.size == 1),
      s"integration changes which frozen domains are the same in ${design.spec.root.show}"
    )
    design.spec.boundaries.foreach { boundary =>
      val expected = design.boundaryEdge(boundary)
      val actual   = parent.edgeAt(port(boundary).id)
      require(
        actual.down == expected.down && actual.up == expected.up &&
          actual.interface == expected.interface && actual.edge == expected.edge,
        s"integration changes the frozen contract at ${boundary.id.show}"
      )
    }

private[syntheke] object DesignIntegration:
  def combine(children: Vector[ResolvedDesign], parent: ResolvedDesign): ResolvedDesign =
    val parts = children :+ parent
    val modules = parts.flatMap(_.spec.moduleOrder)
    require(modules.distinct.size == modules.size, "integrated module paths overlap")
    val declarations = parts.flatMap(_.spec.binds)
    val binds = declarations.zipWithIndex.map((b, order) => b.copy(order = order))
    val remapped = declarations.map(_.bindId).zip(binds.map(_.bindId)).toMap
    def bind(id: BindId): BindId = remapped.getOrElse(id, id)
    def origin(value: PlanOrigin): PlanOrigin = value match
      case PlanOrigin.Design(id) => PlanOrigin.Design(bind(id))
      case value => value
    val spec = parent.spec.copy(
      modules = parts.flatMap(_.spec.modules).toMap,
      moduleOrder = Vector(parent.spec.root) ++ modules.filterNot(_ == parent.spec.root),
      binds = binds)
    val edges = parts.flatMap(_.edges).map(e => e.copy(bind = bind(e.bind)))
    require(spec.generators.map(_.name).distinct.size == spec.generators.size, "distinct generators share a module name")
    val wrappers = spec.moduleOrder.flatMap(spec.wrapper)
    require(wrappers.groupBy(_.moduleName).values.forall(_.map(_.definition).distinct.size == 1),
      "distinct wrapper definitions share a module name")
    val observations = parts.flatMap(_.observations).toMap
    val (plannedPorts, plannedWires, plannedLayers) = Planner.plan(spec, edges, parent.probes, observations)
    val frozenPorts = children.flatMap(_.portPlans).map(p => p.copy(origin = origin(p.origin)))
    val frozenWires = children.flatMap(_.wirePlans).map(w => w.copy(origin = origin(w.origin)))
    val frozenModules = children.flatMap(_.spec.moduleOrder).toSet
    require(plannedPorts.filter(p => frozenModules(p.module)).forall(frozenPorts.contains),
      "integration adds ports to a frozen design")
    require(plannedWires.filter(w => frozenModules(w.module)).forall(frozenWires.contains),
      "integration changes wiring inside a frozen design")
    val layers = children.flatMap(_.layerDecls).foldLeft(plannedLayers) { case (acc, (module, tree)) =>
      acc.updated(module, acc.getOrElse(module, LayerTree.empty).merge(tree))
    }
    val edgeBySource = edges.map(e => e.bind.source -> e).toMap
    parent.copy(
      spec = spec,
      edges = edges,
      generatorModules = parts.flatMap(_.generatorModules).map(g => g.copy(
        view = g.view.copy(nodes = g.view.nodes.map(n => n.copy(edge = edgeBySource(n.edge.bind.source)))))),
      portPlans = (frozenPorts ++ plannedPorts).distinct,
      wirePlans = (frozenWires ++ plannedWires).distinct,
      layerDecls = layers,
      observations = observations)
