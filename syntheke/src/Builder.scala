package me.jiuyang.syntheke

import scala.collection.mutable

private sealed trait FrameDraft

private final class WrapperDraft(val moduleName: String) extends FrameDraft:
  val children = mutable.ArrayBuffer.empty[BuildFrame]

private final case class NodeEntry(
  draft:       NodeDraft[?],
  memberships: Vector[Membership],
  loc:         SourceLoc,
  computation: Option[NodeComputation])

private final class GeneratorDraft(val definition: GeneratorDefinition[?]) extends FrameDraft:
  val nodes  = mutable.ArrayBuffer.empty[NodeEntry]
  val probes = mutable.ArrayBuffer.empty[ProbeSpec[?]]
  var params = Option.empty[(EdgeView, DomainGraph) => Either[Violation, Any]]

private final class BuildFrame(val session: BuildSession, val id: ModuleId, val draft: FrameDraft):
  val domains = mutable.ArrayBuffer.empty[Domain[?]]

  def wrapper: WrapperDraft = draft match
    case value: WrapperDraft => value
    case _                   => throw IllegalStateException(s"${id.show} is a generator module")

  def generator: GeneratorDraft = draft match
    case value: GeneratorDraft => value
    case _                     => throw IllegalStateException(s"${id.show} is a wrapper module")

  var spec = Option.empty[ModuleSpec]

/** One design's build: the frames of its module tree, its binds in declaration order, its boundaries and the frozen
  * designs it instantiates.
  */
private[syntheke] final class BuildSession(val rootId: ModuleId):
  val boundaryId: ModuleId = rootId / "$boundary"
  private val boundaryNodes = mutable.ArrayBuffer.empty[NodeSpec]
  private val boundaries    = mutable.ArrayBuffer.empty[Boundary[?]]
  private val imported      = mutable.ArrayBuffer.empty[BoundaryImport]
  private val probeExports  = mutable.ArrayBuffer.empty[ResolvedProbe[?]]
  private val binds         = mutable.ArrayBuffer.empty[BindDecl]
  private val stack         = mutable.ArrayBuffer.empty[BuildFrame]
  private var root          = Option.empty[BuildFrame]
  private var frozen        = false

  def imports: Vector[BoundaryImport] = imported.toVector

  def importOf(boundary: Boundary[?], loc: SourceLoc): BoundaryImport =
    imports.find(_.contains(boundary)).getOrElse(
      fail(s"boundary ${boundary.id.show} is not a public boundary of an instantiated design", loc)
    )

  def instantiate[A](design: Design[A], id: ModuleId): DesignInstance[A] =
    val (definition, publicPorts) = design.frozen
    val placement                 = new DesignPlacement(definition, id)
    imported += new BoundaryImport(placement.resolved, definition)
    new DesignInstance(design.dangles.place(publicPorts, placement), placement.resolved)

  def forwardProbe[P](probe: ResolvedProbe[P], loc: SourceLoc): ResolvedProbe[P] =
    check(imports.exists(_.design.probes.nodes.exists(_ eq probe)), loc)(
      s"probe ${probe.id.show} is not public in an instantiated design"
    )
    probeExports += probe
    probe

  def recordBind(bind: BindDecl): Unit = binds += bind

  def registerBoundary(node: NodeSpec, boundary: Boundary[?]): Unit =
    check(!boundaryNodes.exists(_.name == node.name), node.loc)(s"duplicate boundary '${node.name}'")
    boundaryNodes += node.copy(order = boundaryNodes.size)
    boundaries += boundary

  def resolve(spec: DesignSpec): ResolvedDesign =
    val catalog = imports.map(_.design.probes).reduceOption(_.combined(_))
    val local   = Negotiator.resolve(spec, catalog)
    report("integration", imports.flatMap(_.violations(local)))
    val published = local.probes.published(spec.generatorModules.flatMap(_.probes.map(_.node)).toSet, probeExports.toVector)
    DesignIntegration.combine(imports, local, published)

  def requireCurrent(frame: BuildFrame, what: String, loc: SourceLoc): Unit =
    check(!frozen && stack.lastOption.contains(frame), loc)(s"$what outside module ${frame.id.show}'s body")

  def build[A](rootModuleName: String, loc: SourceLoc)(body: WrapperScope ?=> A): (DesignSpec, A) =
    check(root.isEmpty, loc)("a design builds once")
    DeclaredName.check(rootModuleName, "root wrapper module name", loc)
    val rootFrame = new BuildFrame(this, rootId, new WrapperDraft(rootModuleName))
    root = Some(rootFrame)
    val result    = run(rootFrame) {
      val context = new BuildContext[WrapperMode](rootFrame, Map.empty)
      val value   = body(using context)
      rootFrame.spec = Some(context.wrapperSpec(loc))
      value
    }
    (freeze(rootFrame), result)

  def runChild[R <: BuildMode, A](parent: BuildFrame, child: BuildFrame, context: BuildContext[R], loc: SourceLoc)(
    body:  BuildContext[R] ?=> A
  )(close: => ModuleSpec
  ): A =
    val result = run(child) {
      val value = body(using context)
      child.spec = Some(close)
      value
    }
    requireCurrent(parent, s"close ${child.id.show}", loc)
    parent.wrapper.children += child
    result

  private def run[A](frame: BuildFrame)(body: => A): A =
    stack += frame
    try body
    finally stack.dropRightInPlace(1)

  private def freeze(rootFrame: BuildFrame): DesignSpec =
    def preorder(frame: BuildFrame): Vector[BuildFrame] = frame +: (frame.draft match
      case wrapper: WrapperDraft => wrapper.children.toVector.flatMap(preorder)
      case _: GeneratorDraft     => Vector.empty)
    val frames   = preorder(rootFrame)
    val boundary = Option.when(boundaryNodes.nonEmpty)(
      BoundaryModuleSpec(boundaryId, rootId, false, boundaryNodes.toVector, rootFrame.spec.get.loc)
    )
    frozen = true
    DesignSpec(
      modules = frames.map(f => f.id -> f.spec.get).toMap ++ boundary.map(b => b.id -> b) ++ imports.map(i => i.spec.id -> i.spec),
      moduleOrder = frames.map(_.id) ++ boundary.map(_.id) ++ imports.map(_.spec.id),
      binds = binds.toVector,
      domains = frames.flatMap(_.domains) ++ imports.flatMap(_.domains),
      root = rootId,
      boundaries = boundaries.toVector
    )

/** The scope a module body runs in. `scoped` holds, per scoped kind, the domain the enclosing scopes place
  * modules in.
  */
final class BuildContext[+R <: BuildMode] private[syntheke] (
  private val frame:  BuildFrame,
  private val scoped: Map[DomainKind, Domain[?]]):

  private[syntheke] val id: ModuleId = frame.id

  private def session: BuildSession = frame.session

  private def requireOpen(what: String, loc: SourceLoc): Unit = session.requireCurrent(frame, what, loc)

  private def requireLocal(source: DomainSource[?], role: String, loc: SourceLoc): Unit = source match
    case ref: DomainRef[?] => check(ref.node.module == id, loc)(s"$role reads ${ref.node.show}, which is not a node of ${id.show}")
    case _: Domain[?]      => ()

  private[syntheke] def scope[A](domain: Domain[?], loc: SourceLoc)(body: BuildContext[R] ?=> A): A =
    requireOpen(s"scope of domain ${domain.id.show}", loc)
    check(domain.kind.scoped, loc)(s"${domain.kind.name} domains cannot scope modules")
    body(using new BuildContext[R](frame, scoped.updated(domain.kind, domain)))

  private[syntheke] def declareDomain[K <: DomainKind](kind: K, name: String, origin: Domain.Origin[DomainSource[?]], loc: SourceLoc)
    : Domain[K] =
    requireOpen(s"domain '$name'", loc)
    DeclaredName.check(name, s"domain name in ${id.show}", loc)
    check(!frame.domains.exists(_.id.name == name), loc)(s"duplicate domain '$name' in ${id.show}")
    origin match
      case Domain.Origin.Derived(sources, _) => sources.foreach(requireLocal(_, s"domain '$name'", loc))
      case _                                 => ()
    val domain = new Domain(kind, DomainId(id, name), origin, loc)
    frame.domains += domain
    domain

  private def childId(name: String, loc: SourceLoc): ModuleId =
    requireOpen(s"instance '$name'", loc)
    DeclaredName.check(name, s"instance name in ${id.show}", loc)
    check(
      !frame.wrapper.children.exists(_.id.path.last == name) && !session.imports.exists(_.spec.target == id / name),
      loc
    )(s"duplicate instance name '$name' in ${id.show}")
    id / name

  private[syntheke] def instantiate[A](design: Design[A], name: String, loc: SourceLoc): DesignInstance[A] =
    session.instantiate(design, childId(name, loc))

  private[syntheke] def wrapper[A](name: String, moduleName: String, loc: SourceLoc)(body: WrapperScope ?=> A): A =
    DeclaredName.check(moduleName, s"wrapper module name of '$name' in ${id.show}", loc)
    val child   = new BuildFrame(session, childId(name, loc), new WrapperDraft(moduleName))
    val context = new BuildContext[WrapperMode](child, scoped)
    session.runChild(frame, child, context, loc)(body)(context.wrapperSpec(loc))

  private[syntheke] def generator[FP, A](name: String, definition: GeneratorDefinition[FP], loc: SourceLoc)(
    body: GeneratorScope[FP] ?=> A
  ): A =
    val child   = new BuildFrame(session, childId(name, loc), new GeneratorDraft(definition))
    val context = new BuildContext[GeneratorMode[FP]](child, scoped)
    session.runChild(frame, child, context, loc)(body)(context.generatorSpec(loc))

  private[syntheke] def recordBind(source: OutwardPort[?], target: InwardPort[?], loc: SourceLoc): Unit =
    requireOpen(s"bind ${source.id.show} -> ${target.id.show}", loc)
    session.recordBind(BindDecl(source.id, target.id, id, loc))

  private def reserveName(name: String, loc: SourceLoc): GeneratorDraft =
    requireOpen(s"declaration '$name'", loc)
    DeclaredName.check(name, s"declaration name in ${id.show}", loc)
    val draft = frame.generator
    check(!draft.nodes.exists(_.draft.id.name == name) && !draft.probes.exists(_.node.id.name == name), loc)(
      s"duplicate declaration name '$name' in ${id.show}"
    )
    draft

  private[syntheke] def declareProbe[P](selector: ProbeSelector[?, P], name: String, loc: SourceLoc): ProbeNode[P] =
    val draft = reserveName(name, loc)
    val node  = new ProbeNode(ModuleNodeId(id, name), selector.contract)
    draft.probes += new ProbeSpec(node, selector, loc)
    node

  private[syntheke] def inward(p: Protocol, domains: Seq[DomainSource[?]], name: String, loc: SourceLoc)
    : p.InwardDraft =
    val draft       = reserveName(name, loc)
    val nodeId      = ModuleNodeId(id, name)
    val memberships = membershipsOf(p, nodeId, NodeDirection.Inward, domains, true, loc)
    val node        = new InwardNodeDraft[p.type](p, this.asInstanceOf[BuildContext[? <: GeneratorMode[?]]], nodeId, memberships.map(_.kind))
    draft.nodes += NodeEntry(node, memberships, loc, None)
    node

  private[syntheke] def outward(p: Protocol, domains: Seq[DomainSource[?]], name: String, loc: SourceLoc)
    : p.OutwardDraft =
    val draft       = reserveName(name, loc)
    val nodeId      = ModuleNodeId(id, name)
    val memberships = membershipsOf(p, nodeId, NodeDirection.Outward, domains, true, loc)
    val node        = new OutwardNodeDraft[p.type](p, this.asInstanceOf[BuildContext[? <: GeneratorMode[?]]], nodeId, memberships.map(_.kind))
    draft.nodes += NodeEntry(node, memberships, loc, None)
    node

  /** A node's memberships. An inward carrier receives the kinds its protocol carries through its bind; an outward
    * carrier names the domain it drives. Scoped kinds left out come from the enclosing scope. A hardware member of a
    * physical kind takes it from a node of this module or from a domain this module declares.
    */
  private def membershipsOf(
    protocol:  Protocol,
    node:      ModuleNodeId,
    direction: NodeDirection,
    sources:   Seq[DomainSource[?]],
    hardware:  Boolean,
    loc:       SourceLoc
  ): Vector[Membership] =
    val kinds   = sources.map(_.kind)
    val carried = protocol.carries
    check(kinds.distinct.size == kinds.size, loc)(s"node ${node.show} names a domain kind twice")
    sources.foreach { source =>
      val kind = source.kind
      check(!(carried(kind) && direction == NodeDirection.Inward), loc)(
        s"node ${node.show} receives its ${kind.name} domain through its bind; do not name it"
      )
      requireLocal(source, s"node ${node.show}", loc)
      source match
        case domain: Domain[?] if hardware && kind.physical && !carried(kind) =>
          check(domain.id.module == id, loc)(
            s"node ${node.show} must take its ${kind.name} domain from a node or a domain of ${id.show}, not ${domain.id.show}"
          )
        case _                                                                => ()
    }
    if direction == NodeDirection.Outward then
      carried.foreach(kind => check(kinds.exists(_ eq kind), loc)(s"node ${node.show} must name the ${kind.name} domain it drives"))
    val received =
      if direction == NodeDirection.Inward then carried.toVector.map(Membership(_, None, loc)) else Vector.empty
    val defaults = scoped.toVector.collect {
      case (kind, domain) if !kinds.exists(_ eq kind) && !carried(kind) => Membership(kind, Some(domain), loc)
    }
    sources.toVector.map(source => Membership(source.kind, Some(source), loc)) ++ received ++ defaults

  private[syntheke] def seal[P <: Protocol](node: InwardNodeDraft[P], computation: NodeComputation, loc: SourceLoc)
    : InwardPort[P] =
    sealDraft(node, computation, loc)
    new InwardPort(node.protocol, node.id, node.kinds)

  private[syntheke] def seal[P <: Protocol](node: OutwardNodeDraft[P], computation: NodeComputation, loc: SourceLoc)
    : OutwardPort[P] =
    sealDraft(node, computation, loc)
    new OutwardPort(node.protocol, node.id, node.kinds)

  private def sealDraft(node: NodeDraft[?], computation: NodeComputation, loc: SourceLoc): Unit =
    requireOpen(s"parameters of ${node.id.show}", loc)
    val draft = frame.generator
    val index = draft.nodes.indexWhere(_.draft eq node)
    check(index >= 0, loc)(s"node ${node.id.show} is not a node of ${id.show}")
    check(draft.nodes(index).computation.isEmpty, loc)(s"node ${node.id.show} already has its parameter")
    computation.reads.foreach(read => check(read.module == id, loc)(s"${node.id.show} reads ${read.show}, a node of another module"))
    draft.nodes(index) = draft.nodes(index).copy(computation = Some(computation))

  private[syntheke] def parameters[FP](compute: (EdgeView, DomainGraph) => Either[Violation, FP], loc: SourceLoc): Unit =
    requireOpen(s"parameters of ${id.show}", loc)
    val draft = frame.generator
    check(draft.params.isEmpty, loc)(s"parameters of ${id.show} are already given")
    draft.params = Some(compute)

  private[syntheke] def wrapperSpec(loc: SourceLoc): WrapperModuleSpec =
    val children = frame.wrapper.children.map(_.id.path.last).toVector ++
      session.imports.filter(_.spec.target.parent.contains(id)).map(_.spec.target.path.last)
    WrapperModuleSpec(id, frame.wrapper.moduleName, children, loc, new Object)

  private[syntheke] def generatorSpec(loc: SourceLoc): GeneratorModuleSpec =
    val draft = frame.generator
    val nodes = draft.nodes.toVector.zipWithIndex.map { (entry, order) =>
      val direction = entry.draft match
        case _: InwardNodeDraft[?]  => NodeDirection.Inward
        case _: OutwardNodeDraft[?] => NodeDirection.Outward
      val computation = entry.computation.getOrElse(
        fail(s"node ${entry.draft.id.show} has no parameter: call fixed or derive", entry.loc)
      )
      NodeSpec(entry.draft.id.name, direction, entry.draft.protocol, computation, entry.memberships, order, entry.loc)
    }
    val compute = draft.params.getOrElse(fail(s"generator module ${id.show} never calls parameters(...)", loc))
    GeneratorModuleSpec(id, draft.definition, nodes, compute, loc, draft.probes.toVector)

  private def declareBoundary[P <: Protocol](
    node:              Port[P],
    externalParams:    Any,
    memberships:       ModuleNodeId => Vector[Membership],
    name:              String,
    externalDirection: NodeDirection,
    loc:               SourceLoc
  ): Boundary[P] =
    requireOpen("design boundary", loc)
    check(id == session.rootId, loc)("boundaries belong to the design root")
    DeclaredName.check(name, "boundary name", loc)
    val externalId = ModuleNodeId(session.boundaryId, name)
    val members    = memberships(externalId)
    val publicId   = ModuleNodeId(id, name)
    val boundary   = node match
      case inner: InwardPort[P]  =>
        val external = new OutwardPort(node.protocol, externalId, members.map(_.kind))
        recordBind(external, inner, loc)
        new InwardBoundary(node.protocol, publicId, external)
      case inner: OutwardPort[P] =>
        val external = new InwardPort(node.protocol, externalId, members.map(_.kind))
        recordBind(inner, external, loc)
        new OutwardBoundary(node.protocol, publicId, external)
    session.registerBoundary(
      NodeSpec(name, externalDirection, node.protocol, NodeComputation.Constant(externalParams), members, 0, loc),
      boundary
    )
    boundary

  private[syntheke] def inwardBoundary[P <: Protocol](
    node:            InwardPort[P],
    externalParams:  Any,
    externalDomains: Seq[DomainSource[?]],
    name:            String,
    loc:             SourceLoc
  ): InwardBoundary[P] =
    declareBoundary(
      node,
      externalParams,
      membershipsOf(node.protocol, _, NodeDirection.Outward, externalDomains, false, loc),
      name,
      NodeDirection.Outward,
      loc
    ).asInstanceOf[InwardBoundary[P]]

  private[syntheke] def outwardBoundary[P <: Protocol](
    node:            OutwardPort[P],
    externalParams:  Any,
    externalDomains: Seq[DomainSource[?]],
    name:            String,
    loc:             SourceLoc
  ): OutwardBoundary[P] =
    declareBoundary(
      node,
      externalParams,
      membershipsOf(node.protocol, _, NodeDirection.Inward, externalDomains, false, loc),
      name,
      NodeDirection.Inward,
      loc
    ).asInstanceOf[OutwardBoundary[P]]

  private[syntheke] def forwardBoundary[P <: Protocol](boundary: Boundary[P], name: String, loc: SourceLoc): Boundary[P] =
    val imported = session.importOf(boundary, loc)
    val edge     = imported.design.boundaryEdge(boundary)
    boundary match
      case _: OutwardBoundary[?] =>
        declareBoundary(
          imported.port(boundary).asInstanceOf[Port[P]],
          edge.up,
          _ => imported.memberships(boundary, NodeDirection.Inward, loc),
          name,
          NodeDirection.Inward,
          loc
        )
      case _: InwardBoundary[?]  =>
        declareBoundary(
          imported.port(boundary).asInstanceOf[Port[P]],
          edge.down,
          _ => imported.forwarded(boundary, loc),
          name,
          NodeDirection.Outward,
          loc
        )

  private[syntheke] def forwardProbe[P](probe: ResolvedProbe[P], loc: SourceLoc): ResolvedProbe[P] =
    requireOpen("public probe boundary", loc)
    check(id == session.rootId, loc)("probe boundaries belong to the design root")
    session.forwardProbe(probe, loc)

  private[syntheke] def connectBoundaries[P <: Protocol](
    source: OutwardBoundary[P],
    target: InwardBoundary[P],
    loc:    SourceLoc
  ): Unit =
    recordBind(
      session.importOf(source, loc).port(source).asInstanceOf[OutwardPort[P]],
      session.importOf(target, loc).port(target).asInstanceOf[InwardPort[P]],
      loc
    )

  private[syntheke] def connectBoundary[P <: Protocol](boundary: Boundary[P], node: Port[P], loc: SourceLoc): Unit =
    (session.importOf(boundary, loc).port(boundary), node) match
      case (source: OutwardPort[?], target: InwardPort[?]) => recordBind(source, target, loc)
      case (target: InwardPort[?], source: OutwardPort[?]) => recordBind(source, target, loc)
      case _                                               => fail(s"${boundary.id.show} and ${node.id.show} face the same way", loc)

  /** The domain an instantiated design's boundary is in, as a domain of this design. */
  private[syntheke] def boundaryDomain[K <: DomainKind](boundary: Boundary[?], kind: K, loc: SourceLoc): Domain[K] =
    requireOpen("boundary domain", loc)
    session.importOf(boundary, loc).domain(boundary, kind, loc)
