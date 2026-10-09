package me.jiuyang.syntheke

/** Places a definition-local resolved design at an instance path. Only what planning, elaboration, export and
  * the boundary import read is moved; computations and domains stay as negotiated.
  */
private[syntheke] final class DesignPlacement(definition: ResolvedDesign, root: ModuleId):
  private val spec = definition.spec
  require(spec.root == ModuleId.root, "only a definition-local design can be instantiated")

  private def module(id: ModuleId): ModuleId = ModuleId(root.path ++ id.path)
  private def node(id: ModuleNodeId): ModuleNodeId = id.copy(module = module(id.module))
  private def bind(id: BindId): BindId             = id.copy(source = node(id.source), target = node(id.target))
  private def edge(value: ResolvedEdge): ResolvedEdge = value.copy(bind = bind(value.bind))

  private val originalProbes =
    (spec.generatorModules.flatMap(_.probes.map(_.node)) ++ definition.probes.nodes.map(_.node)).distinct

  private def inward[P <: Protocol](value: InwardPort[P]): InwardPort[P] =
    new InwardPort(value.protocol, node(value.id), value.kinds)
  private def outward[P <: Protocol](value: OutwardPort[P]): OutwardPort[P] =
    new OutwardPort(value.protocol, node(value.id), value.kinds)

  private def placeBoundary[P <: Protocol](value: Boundary[P]): Boundary[P] = value match
    case b: InwardBoundary[P] =>
      new InwardBoundary(b.protocol, outward(b.terminal.asInstanceOf[OutwardPort[P]]))
    case b: OutwardBoundary[P] =>
      new OutwardBoundary(b.protocol, inward(b.terminal.asInstanceOf[InwardPort[P]]))

  private val boundaries: Map[Boundary[?], Boundary[?]] = spec.boundaries.map(b => b -> placeBoundary(b)).toMap
  def inwardBoundary[P <: Protocol](value: InwardBoundary[P]): InwardBoundary[P] =
    boundaries(value).asInstanceOf[InwardBoundary[P]]
  def outwardBoundary[P <: Protocol](value: OutwardBoundary[P]): OutwardBoundary[P] =
    boundaries(value).asInstanceOf[OutwardBoundary[P]]

  private def placeProbe[P](value: ProbeNode[P]): ProbeNode[P] = new ProbeNode(node(value.id), value.contract)
  private val probeNodes: Map[ProbeNode[?], ProbeNode[?]] = originalProbes.map(p => p -> placeProbe(p)).toMap
  private def probe[P](value: ProbeNode[P]): ProbeNode[P] = probeNodes(value).asInstanceOf[ProbeNode[P]]

  private def publicPort(value: ResolvedPublicPort): ResolvedPublicPort = value.copy(id = node(value.id))
  private def resolvedProbe[P](value: ResolvedProbe[P]): ResolvedProbe[P] =
    new ResolvedProbe(probe(value.node), value.parameters, publicPort(value.port), value.implementation)

  private def probeSpec[P](value: ProbeSpec[P]): ProbeSpec[P] = new ProbeSpec(probe(value.node), value.selector, value.loc)

  private def edgeView(value: EdgeView): EdgeView =
    value.copy(module = module(value.module), nodes = value.nodes.map(n => n.copy(node = node(n.node), edge = edge(n.edge))))

  private def moduleSpec(value: ModuleSpec): ModuleSpec = value match
    case w: WrapperModuleSpec   => w.copy(id = module(w.id))
    case b: BoundaryModuleSpec  => b.copy(id = module(b.id), target = module(b.target))
    case g: GeneratorModuleSpec => g.copy(id = module(g.id), probes = g.probes.map(probeSpec))

  val resolved: ResolvedDesign = definition.copy(
    dependencies = definition.dependencies.map((id, design) => module(id) -> design),
    spec = spec.copy(root = root,
      modules = spec.modules.map((id, value) => module(id) -> moduleSpec(value)),
      moduleOrder = spec.moduleOrder.map(module),
      binds = spec.binds.map(b => b.copy(source = node(b.source), target = node(b.target), declaredIn = module(b.declaredIn))),
      boundaries = spec.boundaries.map(boundaries)),
    domains = definition.domains.placed(node),
    edges = definition.edges.map(edge),
    generatorModules = definition.generatorModules.map(g =>
      g.copy(module = module(g.module), view = edgeView(g.view))),
    probes = new ProbeCatalog(definition.probes.nodes.map(resolvedProbe)),
    observations = definition.observations.map { (id, bindings) =>
      module(id) -> new ProbeBindings(bindings.ports.map(p => p.copy(source = node(p.source))))
    },
    portPlans = definition.portPlans.map(p => p.copy(module = module(p.module))),
    wirePlans = definition.wirePlans.map(w => w.copy(module = module(w.module))),
    layerDecls = definition.layerDecls.map((id, tree) => module(id) -> tree))
