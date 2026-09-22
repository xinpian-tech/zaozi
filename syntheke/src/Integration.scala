package me.jiuyang.syntheke

private[syntheke] final class BoundaryImport(val design: ResolvedDesign, val definition: ResolvedDesign, session: BuildSession):
  private val id = design.spec.root.parent.get / s"$$${design.spec.root.path.last}"
  private val originals = design.spec.boundaries.flatMap(_.terminal.nodeDomains).map { use =>
    val declaration = design.domainAttachments.find(_.key == use.key).get.declaration
    design.domains.find(_.id == declaration).get
  }.distinctBy(_.id)
  val domains: Vector[DomainHandle[?]] = originals.zipWithIndex.map { (resolved, i) =>
    val domain = resolved.domain
    new DomainHandle(domain, DomainDeclId(id, s"domain$i"), session.owner, Vector.empty, i, resolved.loc)(
      _ => Right((resolved.value.asInstanceOf[domain.Value], None)))
  }
  private val domainOf = originals.map(_.id).zip(domains).toMap
  private val tokens = design.spec.boundaries.flatMap { boundary =>
    boundary.terminal.nodeDomains.map { original =>
      val domain = original.domain
      val key = NodeDomainKey(ModuleNodeId(id, boundary.id.name), domain.key)
      original.key -> new NodeDomain(domain, key, session.owner)
    }
  }.toMap
  private val inputs = design.spec.boundaries.collect { case boundary: InwardBoundary[?] => boundary }.flatMap { boundary =>
    boundary.terminal.nodeDomains.filter(use => boundary.protocol.carries.exists(_ eq use.domain)).map { use =>
      design.boundaryDomain(boundary, use.domain).id -> tokens(use.key)
    }
  }.groupMap(_._1)(_._2).view.mapValues(_.head).toMap
  val constraints: Vector[ConstraintSpec] = originals.flatMap { resolved =>
    val handle = domainOf(resolved.id)
    resolved.requirements.map { requirement =>
      ConstraintSpec(DomainContributor.Module(id),
        new Constraint.Required(handle)(requirement.value.asInstanceOf[handle.domain.Requirement], resolved.loc), 0)
    }
  }
  private def domainSpecs(boundary: Boundary[?], node: ModuleNodeId, direction: NodeDirection,
    loc: (sourcecode.File, sourcecode.Line), forwarding: Boolean): Vector[NodeDomainSpec] =
    boundary.terminal.nodeDomains.zipWithIndex.map { (original, order) =>
      val domain = original.domain
      val attachment = design.domainAttachments.find(_.key == original.key).get
      val key = NodeDomainKey(node, domain.key)
      val carried = boundary.protocol.carries.exists(_ eq domain)
      val source =
        if forwarding && carried && direction == NodeDirection.Outward then domainOf(attachment.declaration)
        else inputs.getOrElse(attachment.declaration, domainOf(attachment.declaration))
      val selector =
        if carried && direction == NodeDirection.Inward then DomainSelectorSpec.CarrierIn
        else DomainSelectorSpec.Frozen(source, attachment.provenance)
      val token = if forwarding then new NodeDomain(domain, key, session.owner) else tokens(original.key)
      NodeDomainSpec(key, domain, selector, order, loc, token)
    }
  private val entries = design.spec.boundaries.zipWithIndex.map { (boundary, order) =>
    val protocol = boundary.protocol
    val edge = design.boundaryEdge(boundary)
    val outward = boundary.isInstanceOf[OutwardBoundary[?]]
    val direction = if outward then NodeDirection.Outward else NodeDirection.Inward
    val nodeId = ModuleNodeId(id, boundary.id.name)
    val loc = design.spec.modules(design.spec.root).loc
    val uses = domainSpecs(boundary, nodeId, direction, loc, false)
    val capability = new NodeCapability
    val port: Port[?] =
      if outward then new OutwardPort(protocol, nodeId, session.owner, capability, uses.map(_.capability))
      else new InwardPort(protocol, nodeId, session.owner, capability, uses.map(_.capability))
    val node = NodeSpec(nodeId.name, direction, protocol, NodeComputation.Constant(if outward then edge.down else edge.up),
      uses, order, loc, capability)
    (boundary.terminal, port, node)
  }
  val ports: Map[Port[?], Port[?]] = entries.map((original, port, _) => original -> port).toMap
  val spec: BoundaryModuleSpec = BoundaryModuleSpec(id, design.spec.root, true, entries.map(_._3), design.spec.modules(design.spec.root).loc)
  def contains(boundary: Boundary[?]): Boolean = ports.contains(boundary.terminal)
  def port(boundary: Boundary[?]): Port[?] = ports(boundary.terminal)
  def forwardDomains(boundary: Boundary[?], node: ModuleNodeId, direction: NodeDirection,
    loc: (sourcecode.File, sourcecode.Line)): Vector[NodeDomainSpec] = domainSpecs(boundary, node, direction, loc, true)

  def validate(parent: ResolvedDesign): Unit =
    val identities = design.spec.boundaries.flatMap { boundary =>
      boundary.terminal.nodeDomains.map { use =>
        val expected = design.boundaryDomain(boundary, use.domain).id
        val actual = parent.domainAttachments.find(_.key == NodeDomainKey(port(boundary).id, use.domain.key)).get.declaration
        expected -> actual
      }
    }.distinct
    require(identities.groupMap(_._1)(_._2).values.forall(_.size == 1) &&
      identities.groupMap(_._2)(_._1).values.forall(_.size == 1),
      s"integration changes frozen domain identity relationships in ${design.spec.root.show}")
    design.spec.boundaries.foreach { boundary =>
      val expected = design.boundaryEdge(boundary)
      val actual = parent.edgeAt(port(boundary).id)
      require(actual.down == expected.down && actual.up == expected.up &&
        actual.interface == expected.interface && actual.edge == expected.edge,
        s"integration changes the frozen contract at ${boundary.id.show}")
      boundary.terminal.nodeDomains.foreach { use =>
        val frozen = design.boundaryDomain(boundary, use.domain)
        val attached = parent.domainAttachments.find(_.key == NodeDomainKey(port(boundary).id, use.domain.key)).get
        val effective = parent.domains.find(_.id == attached.declaration).get
        require(effective.value == frozen.value,
          s"integration changes the frozen ${use.domain.key.show} domain at ${boundary.id.show}")
      }
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
    def provenance(p: AttachmentProvenance): AttachmentProvenance = p match
      case p: AttachmentProvenance.CarrierIn => p.copy(bind = bind(p.bind), anchor = provenance(p.anchor))
      case p: AttachmentProvenance.Follow => p.copy(anchor = provenance(p.anchor))
      case p: AttachmentProvenance.CarrierOut => p.copy(followed = p.followed.map(f => f.copy(anchor = provenance(f.anchor))))
      case p => p
    def contributor(source: DomainContributor): DomainContributor = source match
      case DomainContributor.Bind(id) => DomainContributor.Bind(bind(id))
      case source => source
    def origin(value: PlanOrigin): PlanOrigin = value match
      case PlanOrigin.Design(id) => PlanOrigin.Design(bind(id))
      case value => value
    def module(value: ModuleSpec): ModuleSpec = value match
      case boundary: BoundaryModuleSpec => boundary.copy(nodes = boundary.nodes.map(n => n.copy(
        nodeDomains = n.nodeDomains.map(d => d.copy(selector = d.selector match
          case DomainSelectorSpec.Frozen(handle, p) => DomainSelectorSpec.Frozen(handle, provenance(p))
          case selector => selector)))))
      case value => value
    val constraints = parts.flatMap(_.constraints).map(c => c.copy(source = contributor(c.source)))
    val spec = parent.spec.copy(
      modules = parts.flatMap(_.spec.modules).map((id, value) => id -> module(value)).toMap,
      moduleOrder = Vector(parent.spec.root) ++ modules.filterNot(_ == parent.spec.root),
      binds = binds,
      domainDecls = parts.flatMap(_.spec.domainDecls),
      constraints = parts.flatMap(_.spec.constraints).map(c => c.copy(source = contributor(c.source))))
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
      domains = parts.flatMap(_.domains).map(d => d.copy(requirements = d.requirements.map(r => r.copy(source = contributor(r.source))))),
      domainAttachments = parts.flatMap(_.domainAttachments).map(a => a.copy(provenance = provenance(a.provenance))),
      domainChecks = parts.flatMap(_.domainChecks).map(_.map(contributor, bind)),
      constraints = constraints,
      edges = edges,
      generatorModules = parts.flatMap(_.generatorModules).map(g => g.copy(
        view = g.view.copy(nodes = g.view.nodes.map(n => n.copy(edge = edgeBySource(n.edge.bind.source)))))),
      portPlans = (frozenPorts ++ plannedPorts).distinct,
      wirePlans = (frozenWires ++ plannedWires).distinct,
      layerDecls = layers,
      observations = observations)
