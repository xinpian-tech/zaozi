package me.jiuyang.syntheke

private[syntheke] final class DesignPlacement(definition: ResolvedDesign, root: ModuleId):
  private val spec = definition.spec
  require(spec.root == ModuleId.root, "only a definition-local design can be instantiated")

  private def module(id: ModuleId): ModuleId = ModuleId(root.path ++ id.path)
  private def local(id: ModuleId): ModuleId =
    require(root.isAncestorOf(id), s"${id.show} is outside ${root.show}")
    ModuleId(id.path.drop(root.path.size))
  private def node(id: ModuleNodeId): ModuleNodeId = id.copy(module = module(id.module))
  private def declaration(id: DomainDeclId): DomainDeclId = id.copy(module = module(id.module))
  private def key(id: NodeDomainKey): NodeDomainKey = id.copy(node = node(id.node))
  private def bind(id: BindId): BindId = id.copy(source = node(id.source), target = node(id.target))
  private def edge(value: ResolvedEdge): ResolvedEdge = value.copy(bind = bind(value.bind))

  private val originalProbes = (spec.generatorModules.flatMap(_.probes.map(_.node)) ++ definition.probes.nodes.map(_.node)).distinct
  private val owners = (Vector(spec.owner) ++ spec.domainDecls.map(_.owner) ++ spec.nodeDomains.map(_.capability.owner) ++
    originalProbes.map(_.owner) ++ definition.generatorModules.flatMap(g => Vector(g.view.owner, g.domainView.owner)))
    .distinct.map(_ -> new DesignOwner).toMap
  private val originalOwners = owners.map(_.swap)
  private val capabilities = spec.nodeModules.flatMap(_.nodes).map(_.capability).distinct.map(_ -> new NodeCapability).toMap
  private val originalCapabilities = capabilities.map(_.swap)
  private val uses: Map[NodeDomain[?], NodeDomain[?]] = spec.nodeDomains.map(_.capability).distinct.map { original =>
    original -> new NodeDomain(original.domain, key(original.key), owners(original.owner))
  }.toMap

  private def placeHandle[D <: Domain](original: DomainHandle[D], reads: Vector[DomainReadable[?]]): DomainHandle[D] =
    new DomainHandle(original.domain, declaration(original.id), owners(original.owner), reads, original.order, original.loc)(
      view => original.run(localView(view)))

  private val handles: Map[DomainHandle[?], DomainHandle[?]] =
    @annotation.tailrec
    def loop(pending: Vector[DomainHandle[?]], placed: Map[DomainHandle[?], DomainHandle[?]]): Map[DomainHandle[?], DomainHandle[?]] =
      if pending.isEmpty then placed
      else
        val (ready, rest) = pending.partition(_.reads.forall {
          case h: DomainHandle[?] => placed.contains(h)
          case _: NodeDomain[?] => true
        })
        require(ready.nonEmpty, "frozen domain declarations contain cyclic identity dependencies")
        val next = ready.map { h =>
          h -> placeHandle(h, h.reads.map {
            case source: DomainHandle[?] => placed(source)
            case source: NodeDomain[?] => uses(source)
          })
        }
        loop(rest, placed ++ next)
    loop(spec.domainDecls, Map.empty)

  private val readables: Map[DomainReadable[?], DomainReadable[?]] =
    handles.map((a, b) => (a: DomainReadable[?]) -> (b: DomainReadable[?])) ++ uses
  private val originalReadables = readables.map(_.swap)

  def handle[D <: Domain](value: DomainHandle[D]): DomainHandle[D] = handles(value).asInstanceOf[DomainHandle[D]]
  private def use[D <: Domain](value: NodeDomain[D]): NodeDomain[D] = uses(value).asInstanceOf[NodeDomain[D]]
  private def readable(value: DomainReadable[?]): DomainReadable[?] = readables(value)

  private def localView(view: DomainView): DomainView =
    new DomainView(local(view.module), originalOwners(view.owner), view.entries.map((token, value) => originalReadables(token) -> value))

  private def domainView(view: DomainView): DomainView =
    new DomainView(module(view.module), owners(view.owner), view.entries.map((token, value) => readable(token) -> domain(value)))

  private def constraint(value: Constraint): Constraint = value match
    case r: Constraint.Required[?] =>
      val source = readable(r.source)
      new Constraint.Required(source)(r.value.asInstanceOf[source.domain.Requirement], r.loc)
    case c: Constraint.Check => new Constraint.Check(c.reads.map(readable), view => c.run(localView(view)), c.loc)

  private def contributor(value: DomainContributor): DomainContributor = value match
    case DomainContributor.Node(id) => DomainContributor.Node(node(id))
    case DomainContributor.Declaration(id) => DomainContributor.Declaration(declaration(id))
    case DomainContributor.Module(id) => DomainContributor.Module(module(id))
    case DomainContributor.Bind(id) => DomainContributor.Bind(bind(id))

  private def constraintSpec(value: ConstraintSpec): ConstraintSpec =
    value.copy(source = contributor(value.source), constraint = constraint(value.constraint))

  private def provenance(value: AttachmentProvenance): AttachmentProvenance = value match
    case AttachmentProvenance.Direct(id) => AttachmentProvenance.Direct(declaration(id))
    case AttachmentProvenance.Contextual(id, at) => AttachmentProvenance.Contextual(declaration(id), module(at))
    case AttachmentProvenance.Follow(targets, anchor) => AttachmentProvenance.Follow(targets.map(node), provenance(anchor))
    case AttachmentProvenance.CarrierOut(id, followed) =>
      AttachmentProvenance.CarrierOut(declaration(id), followed.map(f =>
        AttachmentProvenance.Follow(f.targets.map(node), provenance(f.anchor))))
    case AttachmentProvenance.CarrierIn(b, source, id, anchor) =>
      AttachmentProvenance.CarrierIn(bind(b), node(source), declaration(id), provenance(anchor))

  private def domain(value: ResolvedDomain): ResolvedDomain =
    value.copy(id = declaration(value.id), predecessors = value.predecessors.map(declaration),
      requirements = value.requirements.map(r => r.copy(source = contributor(r.source))))

  private def selector(value: DomainSelectorSpec): DomainSelectorSpec = value match
    case DomainSelectorSpec.Direct(h) => DomainSelectorSpec.Direct(handle(h))
    case DomainSelectorSpec.Contextual(h, at) => DomainSelectorSpec.Contextual(handle(h), module(at))
    case DomainSelectorSpec.Follow(u) => DomainSelectorSpec.Follow(use(u))
    case DomainSelectorSpec.CarrierOut(source) => DomainSelectorSpec.CarrierOut(readable(source))
    case DomainSelectorSpec.Frozen(source, p) => DomainSelectorSpec.Frozen(readable(source), provenance(p))
    case DomainSelectorSpec.CarrierIn => DomainSelectorSpec.CarrierIn

  private def token(value: ReadToken): ReadToken = value match
    case r: DownReader[?] => new DownReader[r.Value](node(r.node), capabilities(r.nodeCapability), owners(r.owner0))
    case r: UpReader[?] => new UpReader[r.Value](node(r.node), capabilities(r.nodeCapability), owners(r.owner0))
    case u: NodeDomain[?] => use(u)

  private def computation(value: NodeComputation): NodeComputation = value match
    case c: NodeComputation.Constant => c
    case NodeComputation.Derived(plan, compute) =>
      val tokens = plan.tokens.map(t => t -> token(t))
      NodeComputation.Derived(new ReadPlan(tokens.map(_._2)), values =>
        compute(new ReadValues(tokens.map((before, after) => before -> values.lookup(after)).toMap))
          .map((result, constraints) => result -> constraints.map(constraint)))

  private def nodeSpec(value: NodeSpec): NodeSpec =
    value.copy(capability = capabilities(value.capability), computation = computation(value.computation),
      nodeDomains = value.nodeDomains.map(d =>
        d.copy(key = key(d.key), selector = selector(d.selector), capability = use(d.capability))))

  def inward[P <: Protocol](value: InwardPort[P]): InwardPort[P] =
    new InwardPort(value.protocol, node(value.id), owners(value.owner), capabilities(value.capability), value.nodeDomains.map(use))
  def outward[P <: Protocol](value: OutwardPort[P]): OutwardPort[P] =
    new OutwardPort(value.protocol, node(value.id), owners(value.owner), capabilities(value.capability), value.nodeDomains.map(use))

  private def placeBoundary[P <: Protocol](value: Boundary[P]): Boundary[P] = value match
    case b: InwardBoundary[P] =>
      new InwardBoundary(b.protocol, node(b.id), outward(b.terminal.asInstanceOf[OutwardPort[P]]))
    case b: OutwardBoundary[P] =>
      new OutwardBoundary(b.protocol, node(b.id), inward(b.terminal.asInstanceOf[InwardPort[P]]))

  private val boundaries: Map[Boundary[?], Boundary[?]] = spec.boundaries.map(b => b -> placeBoundary(b)).toMap
  def inwardBoundary[P <: Protocol](value: InwardBoundary[P]): InwardBoundary[P] =
    boundaries(value).asInstanceOf[InwardBoundary[P]]
  def outwardBoundary[P <: Protocol](value: OutwardBoundary[P]): OutwardBoundary[P] =
    boundaries(value).asInstanceOf[OutwardBoundary[P]]

  private def placeProbe[P](value: ProbeNode[P]): ProbeNode[P] =
    new ProbeNode[P](node(value.id), owners(value.owner))(using value.parameterType, value.parameterWriter)
  private val probeNodes: Map[ProbeNode[?], ProbeNode[?]] = originalProbes.map(p => p -> placeProbe(p)).toMap
  def probe[P](value: ProbeNode[P]): ProbeNode[P] = probeNodes(value).asInstanceOf[ProbeNode[P]]

  private val publicPorts = (definition.probes.ports ++ definition.observations.values.toVector.flatMap(_.nodes)).distinct
    .map(p => p -> new ResolvedPublicPort(node(p.id), p.reference)).toMap
  private def resolvedProbe[P](value: ResolvedProbe[P]): ResolvedProbe[P] =
    new ResolvedProbe(probe(value.node), value.parameters, publicPorts(value.port), value.implementation)

  private def probeSpec[P](value: ProbeSpec[P]): ProbeSpec[P] = new ProbeSpec(probe(value.node), value.resolve, value.loc)

  private def edgeView(value: EdgeView): EdgeView =
    value.copy(module = module(value.module), owner = owners(value.owner),
      nodes = value.nodes.map(n => n.copy(node = node(n.node), edge = edge(n.edge), capability = capabilities(n.capability))))
  private def localEdges(value: EdgeView): EdgeView =
    value.copy(module = local(value.module), owner = originalOwners(value.owner), nodes = value.nodes.map(n =>
      n.copy(node = n.node.copy(module = local(n.node.module)), capability = originalCapabilities(n.capability),
        edge = n.edge.copy(bind = n.edge.bind.copy(
          source = n.edge.bind.source.copy(module = local(n.edge.bind.source.module)),
          target = n.edge.bind.target.copy(module = local(n.edge.bind.target.module)))))))

  private def moduleSpec(value: ModuleSpec): ModuleSpec = value match
    case w: WrapperModuleSpec => w.copy(id = module(w.id))
    case b: BoundaryModuleSpec => b.copy(id = module(b.id), target = module(b.target), nodes = b.nodes.map(nodeSpec))
    case g: GeneratorModuleSpec =>
      g.copy(id = module(g.id), nodes = g.nodes.map(nodeSpec), probes = g.probes.map(probeSpec),
        parameters = (edges, domains) => g.parameters(localEdges(edges), localView(domains)))

  private def origin(value: PlanOrigin): PlanOrigin = value match
    case PlanOrigin.Design(id) => PlanOrigin.Design(bind(id))
    case PlanOrigin.Verification(id) => PlanOrigin.Verification(node(id))
    case PlanOrigin.ProbeRead(id) => PlanOrigin.ProbeRead(node(id))
    case PlanOrigin.Observation(id) => PlanOrigin.Observation(node(id))

  val resolved: ResolvedDesign = definition.copy(
    dependencies = definition.dependencies.map((id, design) => module(id) -> design),
    spec = spec.copy(owner = owners(spec.owner), root = root,
      modules = spec.modules.map((id, value) => module(id) -> moduleSpec(value)),
      moduleOrder = spec.moduleOrder.map(module),
      binds = spec.binds.map(b => b.copy(source = node(b.source), target = node(b.target), declaredIn = module(b.declaredIn))),
      domainDecls = spec.domainDecls.map(handle), constraints = spec.constraints.map(constraintSpec),
      boundaries = spec.boundaries.map(boundaries)),
    domains = definition.domains.map(domain),
    domainChecks = definition.domainChecks.map(_.map(contributor, bind)),
    domainAttachments = definition.domainAttachments.map(a =>
      a.copy(key = key(a.key), declaration = declaration(a.declaration), provenance = provenance(a.provenance))),
    constraints = definition.constraints.map(constraintSpec),
    edges = definition.edges.map(edge),
    generatorModules = definition.generatorModules.map(g =>
      g.copy(module = module(g.module), view = edgeView(g.view), domainView = domainView(g.domainView))),
    probes = new ProbeCatalog(definition.probes.ports.map(publicPorts), definition.probes.nodes.map(resolvedProbe)),
    observations = definition.observations.map { (id, bindings) =>
      module(id) -> new ProbeBindings(bindings.nodes.map(publicPorts), bindings.ports.map(p => p.copy(source = node(p.source))))
    },
    portPlans = definition.portPlans.map(p => p.copy(module = module(p.module), origin = origin(p.origin))),
    wirePlans = definition.wirePlans.map(w => w.copy(module = module(w.module), origin = origin(w.origin))),
    layerDecls = definition.layerDecls.map((id, tree) => module(id) -> tree))
