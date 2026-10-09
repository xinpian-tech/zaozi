package me.jiuyang.syntheke

import scala.collection.mutable
import upickle.default.Writer

private sealed trait FrameDraft

private final class WrapperDraft(val moduleName: String) extends FrameDraft:
  val children = mutable.ArrayBuffer.empty[BuildFrame]
  val binds    = mutable.ArrayBuffer.empty[BindDecl]

private final case class NodeEntry(
  draft:       NodeDraft[?],
  memberships: Vector[Membership],
  loc:         (sourcecode.File, sourcecode.Line),
  computation: Option[NodeComputation])

private final class GeneratorDraft(val definition: GeneratorDefinition[?]) extends FrameDraft:
  var nodes  = Vector.empty[NodeEntry]
  val probes = mutable.ArrayBuffer.empty[ProbeSpec[?]]
  val deps   = mutable.ArrayBuffer.empty[ParamDependencySpec]
  var params: Option[(EdgeView, DomainGraph) => Either[Violation, Any]] = None

  def seal(index: Int, computation: NodeComputation): Unit =
    nodes = nodes.updated(index, nodes(index).copy(computation = Some(computation)))

private final class BuildFrame(
  val session: BuildSession,
  val id:      ModuleId,
  val parent:  Option[BuildFrame],
  val draft:   FrameDraft):

  val domains = mutable.ArrayBuffer.empty[Domain[?]]

  def wrapper: WrapperDraft = draft match
    case value: WrapperDraft => value
    case _                   => throw new IllegalStateException(s"${id.show} is a generator module")

  def generator: GeneratorDraft = draft match
    case value: GeneratorDraft => value
    case _                     => throw new IllegalStateException(s"${id.show} is a wrapper module")

  private var committed: Option[ModuleSpec] = None
  def commit(spec: ModuleSpec): Unit =
    require(committed.isEmpty, s"module ${id.show} is already committed")
    committed = Some(spec)
  def spec: ModuleSpec = committed.getOrElse(throw new IllegalStateException(s"module ${id.show} was not committed"))

private[syntheke] final class BuildSession(val rootId: ModuleId):
  val boundaryId: ModuleId = rootId / "$boundary"
  private val boundaryNodes = mutable.ArrayBuffer.empty[NodeSpec]
  private val boundaries    = mutable.ArrayBuffer.empty[Boundary[?]]
  private val imported      = mutable.ArrayBuffer.empty[BoundaryImport]
  private val probeExports  = mutable.ArrayBuffer.empty[ResolvedProbe[?]]
  def imports: Vector[BoundaryImport] = imported.toVector
  def importOf(boundary: Boundary[?]): BoundaryImport =
    imports
      .find(_.contains(boundary))
      .getOrElse(
        throw IllegalArgumentException(s"boundary ${boundary.id.show} is not a public boundary of an instantiated design")
      )
  def instantiate[A](design: Design[A], id: ModuleId): DesignInstance[A] =
    val (definition, publicPorts) = design.frozen
    val placement                 = new DesignPlacement(definition, id)
    val resolved                  = placement.resolved
    val ports                     = design.dangles.place(publicPorts, placement)
    imported += new BoundaryImport(resolved, definition)
    new DesignInstance(ports, resolved)
  def forwardProbe[P](probe: ResolvedProbe[P]): ResolvedProbe[P] =
    require(
      imports.exists(_.design.probes.nodes.exists(_ eq probe)),
      s"Probe ${probe.id.show} is not public in an instantiated design"
    )
    probeExports += probe
    probe
  def resolve(spec: DesignSpec): ResolvedDesign =
    val catalog      = imports.map(_.design.probes).reduceOption(_.combined(_))
    val local        = Negotiator.resolve(spec, catalog)
    imports.foreach(_.validate(local))
    val localProbes  = spec.generatorModules.flatMap(_.probes.map(_.node)).toSet[ProbeNode[?]]
    val publicProbes = local.probes.published(localProbes, probeExports.toVector)
    DesignIntegration.combine(
      imports.map(_.design),
      local.copy(probes = publicProbes, dependencies = imports.map(i => i.design.spec.root -> i.definition))
    )
  def registerBoundary(node: NodeSpec, boundary: Boundary[?]): Unit =
    require(!boundaryNodes.exists(_.name == node.name), s"duplicate boundary '${node.name}'")
    boundaryNodes += node.copy(order = boundaryNodes.size)
    boundaries += boundary

  private var frozen        = false
  private var root          = Option.empty[BuildFrame]
  private val stack         = mutable.ArrayBuffer.empty[BuildFrame]
  private var nextBindOrder = 0

  def allocateBindOrder(): Int =
    val value = nextBindOrder
    nextBindOrder += 1
    value

  private def enter(frame: BuildFrame): Unit =
    require(!frozen, "Design build is no longer active")
    stack += frame

  private def leave(frame: BuildFrame): Unit =
    require(stack.lastOption.contains(frame), s"module ${frame.id.show} is not the current build frame")
    stack.dropRightInPlace(1)

  def requireCurrent(frame: BuildFrame, what: String): Unit =
    require(!frozen && stack.lastOption.contains(frame), s"$what outside module ${frame.id.show}'s builder scope")

  private[syntheke] def build[A](
    rootModuleName: String,
    loc:            (sourcecode.File, sourcecode.Line)
  )(body:           WrapperScope ?=> A
  ): (DesignSpec, A) =
    require(root.isEmpty, "a BuildSession can build only one Design")
    DeclaredName.require(rootModuleName, "root wrapper module name")
    val rootFrame   = new BuildFrame(this, rootId, None, new WrapperDraft(rootModuleName))
    root = Some(rootFrame)
    val rootContext = new BuildContext[WrapperMode](rootFrame, Map.empty)
    enter(rootFrame)
    val result      =
      try
        val result = body(
          using rootContext
        )
        rootFrame.commit(rootContext.wrapperSpec(loc))
        result
      finally leave(rootFrame)
    (freeze(), result)

  private def freeze(): DesignSpec =
    val rootFrame = root.get

    def preorder(frame: BuildFrame): Vector[BuildFrame] =
      frame +: (frame.draft match
        case wrapper: WrapperDraft => wrapper.children.toVector.flatMap(preorder)
        case _:       GeneratorDraft => Vector.empty)

    val frames = preorder(rootFrame)
    frozen = true

    val binds = frames.flatMap {
      _.draft match
        case wrapper: WrapperDraft => wrapper.binds
        case _:       GeneratorDraft => Vector.empty
    }.sortBy(_.order).zipWithIndex.map((decl, order) => decl.copy(order = order))

    DesignSpec(
      modules = frames.map(frame => frame.id -> frame.spec).toMap ++
        Option.when(boundaryNodes.nonEmpty)(
          boundaryId -> BoundaryModuleSpec(boundaryId, rootId, false, boundaryNodes.toVector, rootFrame.spec.loc)
        ) ++
        imports.map(i => i.spec.id -> i.spec),
      moduleOrder = frames.map(_.id) ++ Option.when(boundaryNodes.nonEmpty)(boundaryId) ++ imports.map(_.spec.id),
      binds = binds,
      domains = frames.flatMap(_.domains) ++ imports.flatMap(_.domains),
      root = rootId,
      boundaries = boundaries.toVector
    )

  def childWrapper(parent: BuildFrame, id: ModuleId, moduleName: String): BuildFrame =
    new BuildFrame(this, id, Some(parent), new WrapperDraft(moduleName))

  def childGenerator[FP](parent: BuildFrame, id: ModuleId, definition: GeneratorDefinition[FP]): BuildFrame =
    new BuildFrame(this, id, Some(parent), new GeneratorDraft(definition))

  def runChild[R <: BuildMode, A](
    parent:  BuildFrame,
    child:   BuildFrame,
    context: BuildContext[R]
  )(body:    BuildContext[R] ?=> A
  )(close:   => ModuleSpec
  ): A =
    enter(child)
    val result =
      try
        val value = body(
          using context
        )
        child.commit(close)
        value
      finally leave(child)
    requireCurrent(parent, s"commit child ${child.id.show}")
    parent.wrapper.children += child
    result

/** The scope a module body runs in. `scoped` holds, per scoped kind, the domain the enclosing scopes place
  * modules in.
  */
final class BuildContext[+R <: BuildMode] private[syntheke] (
  private val frame:  BuildFrame,
  private val scoped: Map[DomainKind, Domain[?]]):

  private[syntheke] val id: ModuleId = frame.id

  private def session:                   BuildSession = frame.session
  private def requireOpen(what: String): Unit         = session.requireCurrent(frame, what)

  private def requireLocal(source: DomainSource[?], role: String): Unit = source match
    case ref: DomainRef[?] =>
      require(ref.node.module == id, s"$role reads ${ref.node.show}, which is not a node of ${id.show}")
    case _: Domain[?]      => ()

  private[syntheke] def scope[A](domain: Domain[?])(body: BuildContext[R] ?=> A): A =
    requireOpen(s"scope of domain ${domain.id.show}")
    require(domain.kind.scoped, s"${domain.kind.name} domains cannot scope modules")
    body(
      using new BuildContext[R](frame, scoped.updated(domain.kind, domain))
    )

  private[syntheke] def declareDomain[K <: DomainKind](
    kind:   K,
    name:   String,
    origin: Domain.Origin,
    loc:    (sourcecode.File, sourcecode.Line)
  ): Domain[K] =
    requireOpen(s"domain '$name'")
    DeclaredName.require(name, s"domain name in ${id.show}")
    require(!frame.domains.exists(_.id.name == name), s"duplicate domain '$name' in ${id.show}")
    origin match
      case Domain.Origin.Derived(sources, _) => sources.foreach(requireLocal(_, s"domain '$name'"))
      case _                                 => ()
    val domain = new Domain(kind, DomainId(id, name), origin, loc)
    frame.domains += domain
    domain

  private def requireChildName(name: String): ModuleId =
    requireOpen(s"instance '$name'")
    val draft = frame.wrapper
    DeclaredName.require(name, s"instance name in ${id.show}")
    require(
      !draft.children.exists(_.id.path.last == name) && !session.imports.exists(_.spec.target == id / name),
      s"duplicate child instance name '$name' in ${id.show}"
    )
    id / name

  private[syntheke] def instantiate[A](design: Design[A], name: String): DesignInstance[A] =
    session.instantiate(design, requireChildName(name))

  private[syntheke] def wrapper[A: Dangles](
    name:       String,
    moduleName: String
  )(body:       WrapperScope ?=> A
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): A =
    DeclaredName.require(moduleName, s"wrapper module name at instance '$name' in ${id.show}")
    val childId = requireChildName(name)
    val child   = session.childWrapper(frame, childId, moduleName)
    val context = new BuildContext[WrapperMode](child, scoped)
    session.runChild(frame, child, context)(body)(context.wrapperSpec((file, line)))

  private[syntheke] def generator[FP, A: Dangles](
    name:       String,
    definition: GeneratorDefinition[FP]
  )(body:       GeneratorScope[FP] ?=> A
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): A =
    val childId = requireChildName(name)
    val child   = session.childGenerator(frame, childId, definition)
    val context = new BuildContext[GeneratorMode[FP]](child, scoped)
    session.runChild(frame, child, context)(body)(context.generatorSpec((file, line)))

  private[syntheke] def recordBind(
    source: OutwardPort[?],
    target: InwardPort[?],
    loc:    (sourcecode.File, sourcecode.Line)
  ): Unit =
    requireOpen(s"bind ${source.id.show} -> ${target.id.show}")
    frame.wrapper.binds += BindDecl(session.allocateBindOrder(), source.id, target.id, id, loc)

  private def reserveName(name: String, draft: GeneratorDraft): Unit =
    requireOpen(s"declaration '$name'")
    DeclaredName.require(name, s"declaration name in ${id.show}")
    val taken = draft.nodes.exists(_.draft.id.name == name) || draft.probes.exists(_.node.id.name == name)
    require(!taken, s"duplicate declaration name '$name' in ${id.show}")

  private[syntheke] def declareProbe[FP, P: TypeIdentity: Writer](
    selector: ProbeSelector[FP, P]
  )(name:     String,
    loc:      (sourcecode.File, sourcecode.Line)
  ): ProbeNode[P] =
    val draft = frame.generator
    reserveName(name, draft)
    val node  = new ProbeNode[P](ModuleNodeId(id, name))
    draft.probes += new ProbeSpec(
      node,
      (parameter, declaration) => selector.resolve(parameter.asInstanceOf[FP], declaration),
      loc
    )
    node

  private def requireDraft(node: NodeDraft[?], draft: GeneratorDraft): Int =
    requireOpen(s"node declaration ${node.id.show}")
    val index = draft.nodes.indexWhere(_.draft eq node)
    require(node.id.module == id && index >= 0, s"node ${node.id.show} is not a draft of ${id.show}")
    require(draft.nodes(index).computation.isEmpty, s"node ${node.id.show} is already sealed")
    index

  private[syntheke] def inward(
    p: Protocol
  )(domains: Seq[DomainSource[?]]
  )(name:    String
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): p.InwardDraft =
    val draft       = frame.generator
    reserveName(name, draft)
    val nodeId      = ModuleNodeId(id, name)
    val memberships = membershipsOf(p, nodeId, NodeDirection.Inward, domains, true, (file, line))
    val scope       = this.asInstanceOf[BuildContext[? <: GeneratorMode[?]]]
    val node        = new InwardNodeDraft[p.type](p, scope, nodeId, memberships.map(_.kind))
    draft.nodes = draft.nodes :+ NodeEntry(node, memberships, (file, line), None)
    node

  private[syntheke] def outward(
    p: Protocol
  )(domains: Seq[DomainSource[?]]
  )(name:    String
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): p.OutwardDraft =
    val draft       = frame.generator
    reserveName(name, draft)
    val nodeId      = ModuleNodeId(id, name)
    val memberships = membershipsOf(p, nodeId, NodeDirection.Outward, domains, true, (file, line))
    val scope       = this.asInstanceOf[BuildContext[? <: GeneratorMode[?]]]
    val node        = new OutwardNodeDraft[p.type](p, scope, nodeId, memberships.map(_.kind))
    draft.nodes = draft.nodes :+ NodeEntry(node, memberships, (file, line), None)
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
    loc:       (sourcecode.File, sourcecode.Line)
  ): Vector[Membership] =
    val kinds   = sources.map(_.kind)
    require(kinds.distinct.size == kinds.size, s"node ${node.show} names a domain kind twice")
    val carried = protocol.carries
    sources.foreach { source =>
      val kind = source.kind
      require(
        !(carried(kind) && direction == NodeDirection.Inward),
        s"node ${node.show} receives its ${kind.name} domain through its bind; do not name it"
      )
      requireLocal(source, s"node ${node.show}")
      source match
        case domain: Domain[?] if hardware && kind.physical && !carried(kind) =>
          require(
            domain.id.module == id,
            s"node ${node.show} must take its ${kind.name} domain from a node or a domain of ${id.show}, " +
              s"not ${domain.id.show}"
          )
        case _                                                                => ()
    }
    if direction == NodeDirection.Outward then
      carried.foreach(kind =>
        require(kinds.exists(_ eq kind), s"node ${node.show} must name the ${kind.name} domain it drives")
      )
    val received =
      if direction == NodeDirection.Inward then carried.toVector.map(Membership(_, None, loc)) else Vector.empty
    val defaults = scoped.toVector.collect {
      case (kind, domain) if !kinds.exists(_ eq kind) && !carried(kind) => Membership(kind, Some(domain), loc)
    }
    sources.toVector.map(source => Membership(source.kind, Some(source), loc)) ++ received ++ defaults

  private def sealDraft(
    node: NodeDraft[?],
    plan: ReadPlan,
    f:    ReadValues => Either[Violation, Any]
  ): Unit =
    val draft        = frame.generator
    val index        = requireDraft(node, draft)
    plan.tokens.foreach { case r: Reader[?] =>
      require(r.node.module == id, s"${node.id.show}: parameter source ${r.node.show} is not local to ${id.show}")
    }
    val dependencies = plan.tokens.collect {
      case r: DownReader[?] => r.node.name -> node.id.name
      case r: UpReader[?]   => node.id.name -> r.node.name
    }.distinct.filterNot((from, to) => draft.deps.exists(d => d.from == from && d.to == to))
    draft.deps ++= dependencies.map((from, to) => ParamDependencySpec(from, to, draft.nodes(index).loc))
    draft.seal(index, NodeComputation.Derived(plan, f))

  private[syntheke] def fixed[P <: Protocol](node: InwardNodeDraft[P], value: Any): InwardPort[P] =
    frame.generator.seal(requireDraft(node, frame.generator), NodeComputation.Constant(value))
    new InwardPort(node.protocol, node.id, node.kinds)

  private[syntheke] def fixed[P <: Protocol](node: OutwardNodeDraft[P], value: Any): OutwardPort[P] =
    frame.generator.seal(requireDraft(node, frame.generator), NodeComputation.Constant(value))
    new OutwardPort(node.protocol, node.id, node.kinds)

  private[syntheke] def seal[P <: Protocol](
    node: InwardNodeDraft[P],
    plan: ReadPlan,
    f:    ReadValues => Either[Violation, Any]
  ): InwardPort[P] =
    sealDraft(node, plan, f)
    new InwardPort(node.protocol, node.id, node.kinds)

  private[syntheke] def seal[P <: Protocol](
    node: OutwardNodeDraft[P],
    plan: ReadPlan,
    f:    ReadValues => Either[Violation, Any]
  ): OutwardPort[P] =
    sealDraft(node, plan, f)
    new OutwardPort(node.protocol, node.id, node.kinds)

  private[syntheke] def parameters[FP](compute: (EdgeView, DomainGraph) => Either[Violation, FP]): Unit =
    val draft = frame.generator
    requireOpen(s"parameters of ${id.show}")
    require(draft.params.isEmpty, s"parameters of ${id.show} already set")
    draft.params = Some(compute)

  private[syntheke] def wrapperSpec(loc: (sourcecode.File, sourcecode.Line)): WrapperModuleSpec =
    requireOpen(s"close wrapper ${id.show}")
    val draft = frame.wrapper
    WrapperModuleSpec(
      id,
      draft.moduleName,
      draft.children.map(_.id.path.last).toVector ++
        session.imports.filter(_.spec.target.parent.contains(id)).map(_.spec.target.path.last),
      loc,
      new Object
    )

  private[syntheke] def generatorSpec(loc: (sourcecode.File, sourcecode.Line)): GeneratorModuleSpec =
    requireOpen(s"close generator ${id.show}")
    val draft     = frame.generator
    val nodeSpecs = draft.nodes.zipWithIndex.map { (entry, order) =>
      val node      = entry.draft
      val direction = node match
        case _: InwardNodeDraft[?]  => NodeDirection.Inward
        case _: OutwardNodeDraft[?] => NodeDirection.Outward
      NodeSpec(
        name = node.id.name,
        direction = direction,
        protocol = node.protocol,
        computation = entry.computation.getOrElse(
          throw new IllegalStateException(s"node ${node.id.show}: draft was never sealed")
        ),
        memberships = entry.memberships,
        order = order,
        loc = entry.loc
      )
    }
    val compute   = draft.params.getOrElse(
      throw new IllegalStateException(s"generator module ${id.show}: parameters(...) is mandatory but was never set")
    )
    GeneratorModuleSpec(id, draft.definition, nodeSpecs, draft.deps.toVector, compute, loc, draft.probes.toVector)

  private def declareBoundary[P <: Protocol](
    node:              Port[P],
    externalParams:    Any,
    memberships:       ModuleNodeId => Vector[Membership],
    name:              String,
    externalDirection: NodeDirection,
    loc:               (sourcecode.File, sourcecode.Line)
  ): Boundary[P] =
    requireOpen("design boundary")
    require(id == session.rootId, "boundaries belong to the design root")
    DeclaredName.require(name, "boundary name")
    val externalId   = ModuleNodeId(session.boundaryId, name)
    val members      = memberships(externalId)
    val externalPort = externalDirection match
      case NodeDirection.Outward => new OutwardPort(node.protocol, externalId, members.map(_.kind))
      case NodeDirection.Inward  => new InwardPort(node.protocol, externalId, members.map(_.kind))
    val publicId     = ModuleNodeId(id, name)
    val boundary     = node match
      case _: InwardPort[P]  => new InwardBoundary(node.protocol, publicId, externalPort.asInstanceOf[OutwardPort[P]])
      case _: OutwardPort[P] => new OutwardBoundary(node.protocol, publicId, externalPort.asInstanceOf[InwardPort[P]])
    session.registerBoundary(
      NodeSpec(name, externalDirection, node.protocol, NodeComputation.Constant(externalParams), members, 0, loc),
      boundary
    )
    externalDirection match
      case NodeDirection.Outward =>
        recordBind(externalPort.asInstanceOf[OutwardPort[P]], node.asInstanceOf[InwardPort[P]], loc)
      case NodeDirection.Inward  =>
        recordBind(node.asInstanceOf[OutwardPort[P]], externalPort.asInstanceOf[InwardPort[P]], loc)
    boundary

  private[syntheke] def inwardBoundary[P <: Protocol](
    node:            InwardPort[P],
    externalParams:  Any,
    externalDomains: Seq[DomainSource[?]],
    name:            String,
    loc:             (sourcecode.File, sourcecode.Line)
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
    loc:             (sourcecode.File, sourcecode.Line)
  ): OutwardBoundary[P] =
    declareBoundary(
      node,
      externalParams,
      membershipsOf(node.protocol, _, NodeDirection.Inward, externalDomains, false, loc),
      name,
      NodeDirection.Inward,
      loc
    ).asInstanceOf[OutwardBoundary[P]]

  private[syntheke] def forwardBoundary[P <: Protocol](
    boundary: Boundary[P],
    name:     String,
    loc:      (sourcecode.File, sourcecode.Line)
  ): Boundary[P] =
    val imported  = session.importOf(boundary)
    val node      = imported.port(boundary).asInstanceOf[Port[P]]
    val edge      = imported.design.boundaryEdge(boundary)
    val outward   = boundary.isInstanceOf[OutwardBoundary[?]]
    val direction = if outward then NodeDirection.Inward else NodeDirection.Outward
    declareBoundary(
      node,
      if outward then edge.up else edge.down,
      _ =>
        if outward then imported.memberships(boundary, direction, loc)
        else imported.forwarded(boundary).map(_.copy(loc = loc)),
      name,
      direction,
      loc
    )

  private[syntheke] def forwardProbe[P](probe: ResolvedProbe[P]): ResolvedProbe[P] =
    requireOpen("public Probe boundary")
    require(id == session.rootId, "Probe boundaries belong to the design root")
    session.forwardProbe(probe)

  private[syntheke] def connectBoundaries[P <: Protocol](
    source: OutwardBoundary[P],
    target: InwardBoundary[P],
    loc:    (sourcecode.File, sourcecode.Line)
  ): Unit =
    recordBind(
      session.importOf(source).port(source).asInstanceOf[OutwardPort[P]],
      session.importOf(target).port(target).asInstanceOf[InwardPort[P]],
      loc
    )

  private[syntheke] def connectBoundary[P <: Protocol](
    boundary: Boundary[P],
    node:     Port[P],
    loc:      (sourcecode.File, sourcecode.Line)
  ): Unit =
    val peer = session.importOf(boundary).port(boundary)
    require(peer.protocol eq node.protocol, "boundary protocol mismatch")
    (peer, node) match
      case (source: OutwardPort[?], target: InwardPort[?]) => recordBind(source, target, loc)
      case (target: InwardPort[?], source: OutwardPort[?]) => recordBind(source, target, loc)
      case _                                               => throw IllegalArgumentException("boundary direction mismatch")

  /** The domain an instantiated design's boundary is in, as a domain of this design. */
  private[syntheke] def boundaryDomain[K <: DomainKind](boundary: Boundary[?], kind: K): Domain[K] =
    requireOpen("read boundary domain")
    session.importOf(boundary).domain(boundary, kind)
