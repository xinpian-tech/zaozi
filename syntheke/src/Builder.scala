package me.jiuyang.syntheke

import scala.collection.immutable.VectorMap

import scala.collection.mutable

private sealed trait FrameDraft

private final class WrapperDraft(val moduleName: String) extends FrameDraft:
  val children = mutable.ArrayBuffer.empty[BuildFrame]

private final case class NodeEntry(draft: NodeDraft[?], loc: SourceLoc, computation: Option[NodeComputation])

private final class GeneratorDraft(val definition: GeneratorDefinition[?]) extends FrameDraft:
  val nodes  = mutable.ArrayBuffer.empty[NodeEntry]
  val probes = mutable.ArrayBuffer.empty[ProbeSpec[?]]
  var params = Option.empty[EdgeView => Either[Violation, Any]]

private final class BuildFrame(val session: BuildSession, val id: ModuleId, val draft: FrameDraft):
  def wrapper:   WrapperDraft   = draft.asInstanceOf[WrapperDraft]
  def generator: GeneratorDraft = draft.asInstanceOf[GeneratorDraft]

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
  private val domains       = mutable.ArrayBuffer.empty[Domain[?]]
  private val stack         = mutable.ArrayBuffer.empty[BuildFrame]
  private var root          = Option.empty[BuildFrame]
  private var frozen        = false

  def imports: Vector[BoundaryImport] = imported.toVector

  def importOf(boundary: Boundary[?], loc: SourceLoc): BoundaryImport =
    imports
      .find(_.contains(boundary))
      .getOrElse(
        fail(s"boundary ${boundary.id.show} is not a public boundary of an instantiated design", loc)
      )

  def instantiate[A](design: Design[A], feeds: A => Seq[(Boundary[?], Seq[Domain[?]])], id: ModuleId, loc: SourceLoc)
    : DesignInstance[A] =
    check(!design.building, loc)(s"design ${design.moduleName} instantiates itself")
    val (definition, publicPorts) = design.frozen
    val placement                 = new DesignPlacement(definition, id)
    val ports                     = design.dangles.place(publicPorts, placement)
    val instance                  = new BoundaryImport(definition, placement, feeds(ports), loc)
    imported += instance
    new DesignInstance(ports, instance)

  def forwardProbe[P](probe: ResolvedProbe[P], loc: SourceLoc): ResolvedProbe[P] =
    check(imports.exists(_.probes.nodes.exists(_ eq probe)), loc)(
      s"probe ${probe.id.show} is not public in an instantiated design"
    )
    probeExports += probe
    probe

  def recordBind(bind: BindDecl): Unit = binds += bind

  def declareDomain(domain: Domain[?], loc: SourceLoc): Unit =
    check(!domains.exists(_.id.name == domain.id.name), loc)(s"duplicate domain '${domain.id.name}'")
    domains += domain

  def registerBoundary(node: NodeSpec, boundary: Boundary[?]): Unit =
    check(!boundaryNodes.exists(_.name == node.name), node.loc)(s"duplicate boundary '${node.name}'")
    boundaryNodes += node
    boundaries += boundary

  def resolve(spec: DesignSpec): ResolvedDesign =
    Negotiator.resolve(spec, imports, probeExports.toVector)

  def requireCurrent(frame: BuildFrame, what: String, loc: SourceLoc): Unit =
    check(!frozen && stack.lastOption.contains(frame), loc)(s"$what outside module ${frame.id.show}'s body")

  def build[A](rootModuleName: String, loc: SourceLoc)(body: WrapperScope ?=> A): (DesignSpec, A) =
    check(root.isEmpty, loc)("a design builds once")
    DeclaredName.check(rootModuleName, "root wrapper module name", loc)
    val rootFrame = new BuildFrame(this, rootId, new WrapperDraft(rootModuleName))
    root = Some(rootFrame)
    val result    = run(rootFrame) {
      val context = new BuildContext[WrapperMode](rootFrame)
      val value   = body(
        using context
      )
      rootFrame.spec = Some(context.wrapperSpec(loc))
      value
    }
    (freeze(rootFrame), result)

  def runChild[R <: BuildMode, A](
    parent:  BuildFrame,
    child:   BuildFrame,
    context: BuildContext[R],
    loc:     SourceLoc
  )(body:    BuildContext[R] ?=> A
  )(close:   => ModuleSpec
  ): A =
    val result = run(child) {
      val value = body(
        using context
      )
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
      case wrapper: WrapperDraft   => wrapper.children.toVector.flatMap(preorder)
      case _:       GeneratorDraft => Vector.empty)
    val frames = preorder(rootFrame)
    val boundary = Option.when(boundaryNodes.nonEmpty)(
      BoundaryModuleSpec(boundaryId, rootId, false, boundaryNodes.toVector, rootFrame.spec.get.loc)
    )
    frozen = true
    DesignSpec(
      modules = VectorMap.from(
        frames.map(f => f.id -> f.spec.get) ++ boundary.map(b => b.id -> b) ++ imports.map(i => i.spec.id -> i.spec)
      ),
      binds = binds.toVector,
      domains = domains.toVector,
      root = rootId,
      boundaries = boundaries.toVector
    )

/** The context a module body runs in. */
final class BuildContext[+R <: BuildMode] private[syntheke] (private val frame: BuildFrame):

  private[syntheke] val id: ModuleId = frame.id

  private def session: BuildSession = frame.session

  private def requireOpen(what: String, loc: SourceLoc): Unit = session.requireCurrent(frame, what, loc)

  private[syntheke] def declareDomain[K <: DomainKind](
    kind:       K,
    name:       String,
    definition: kind.Definition,
    loc:        SourceLoc
  ): Domain[K] =
    requireOpen(s"domain '$name'", loc)
    check(id == session.rootId, loc)(
      s"domain '$name' is declared in ${id.show}; domains are declared at the design root"
    )
    DeclaredName.check(name, s"domain name in ${id.show}", loc)
    val domain = new Domain(kind, DomainId(id, name), definition, loc)
    session.declareDomain(domain, loc)
    domain

  private def childId(name: String, loc: SourceLoc): ModuleId =
    requireOpen(s"instance '$name'", loc)
    DeclaredName.check(name, s"instance name in ${id.show}", loc)
    check(
      !frame.wrapper.children.exists(_.id.path.last == name) && !session.imports.exists(_.spec.target == id / name),
      loc
    )(s"duplicate instance name '$name' in ${id.show}")
    id / name

  private[syntheke] def instantiate[A](
    design: Design[A],
    feeds:  A => Seq[(Boundary[?], Seq[Domain[?]])],
    name:   String,
    loc:    SourceLoc
  ): DesignInstance[A] =
    session.instantiate(design, feeds, childId(name, loc), loc)

  private[syntheke] def wrapper[A](name: String, moduleName: String, loc: SourceLoc)(body: WrapperScope ?=> A): A =
    DeclaredName.check(moduleName, s"wrapper module name of '$name' in ${id.show}", loc)
    val child   = new BuildFrame(session, childId(name, loc), new WrapperDraft(moduleName))
    val context = new BuildContext[WrapperMode](child)
    session.runChild(frame, child, context, loc)(body)(context.wrapperSpec(loc))

  private[syntheke] def generator[FP, A](
    name:       String,
    definition: GeneratorDefinition[FP],
    loc:        SourceLoc
  )(body:       GeneratorScope[FP] ?=> A
  ): A =
    val child   = new BuildFrame(session, childId(name, loc), new GeneratorDraft(definition))
    val context = new BuildContext[GeneratorMode[FP]](child)
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

  private[syntheke] def inward(p: Protocol, domains: Seq[Domain[?]], name: String, loc: SourceLoc): p.InwardDraft =
    val draft  = reserveName(name, loc)
    val nodeId = ModuleNodeId(id, name)
    val node   = new InwardNodeDraft[p.type](
      p,
      this.asInstanceOf[BuildContext[? <: GeneratorMode[?]]],
      nodeId,
      domainsOf(p, nodeId, domains, loc)
    )
    draft.nodes += NodeEntry(node, loc, None)
    node

  private[syntheke] def outward(p: Protocol, domains: Seq[Domain[?]], name: String, loc: SourceLoc): p.OutwardDraft =
    val draft  = reserveName(name, loc)
    val nodeId = ModuleNodeId(id, name)
    val node   = new OutwardNodeDraft[p.type](
      p,
      this.asInstanceOf[BuildContext[? <: GeneratorMode[?]]],
      nodeId,
      domainsOf(p, nodeId, domains, loc)
    )
    draft.nodes += NodeEntry(node, loc, None)
    node

  /** The domains a node is in: one per kind, including every kind its protocol carries. */
  private def domainsOf(protocol: Protocol, node: ModuleNodeId, domains: Seq[Domain[?]], loc: SourceLoc)
    : Vector[Domain[?]] =
    val kinds = domains.map(_.kind)
    check(kinds.distinct.size == kinds.size, loc)(s"node ${node.show} names a domain kind twice")
    protocol.carries.foreach { kind =>
      check(kinds.exists(_ eq kind), loc)(s"node ${node.show} must name the ${kind.name} domain its protocol carries")
    }
    domains.toVector

  private[syntheke] def seal[P <: Protocol](node: InwardNodeDraft[P], computation: NodeComputation, loc: SourceLoc)
    : InwardPort[P] =
    sealDraft(node, computation, loc)
    new InwardPort(node.protocol, node.id, node.domains)

  private[syntheke] def seal[P <: Protocol](node: OutwardNodeDraft[P], computation: NodeComputation, loc: SourceLoc)
    : OutwardPort[P] =
    sealDraft(node, computation, loc)
    new OutwardPort(node.protocol, node.id, node.domains)

  private def sealDraft(node: NodeDraft[?], computation: NodeComputation, loc: SourceLoc): Unit =
    requireOpen(s"parameters of ${node.id.show}", loc)
    val draft = frame.generator
    val index = draft.nodes.indexWhere(_.draft eq node)
    check(index >= 0, loc)(s"node ${node.id.show} is not a node of ${id.show}")
    check(draft.nodes(index).computation.isEmpty, loc)(s"node ${node.id.show} already has its parameter")
    computation.reads.foreach(read =>
      check(read.module == id, loc)(s"${node.id.show} reads ${read.show}, a node of another module")
    )
    draft.nodes(index) = draft.nodes(index).copy(computation = Some(computation))

  private[syntheke] def parameters[FP](compute: EdgeView => Either[Violation, FP], loc: SourceLoc): Unit =
    requireOpen(s"parameters of ${id.show}", loc)
    val draft = frame.generator
    check(draft.params.isEmpty, loc)(s"parameters of ${id.show} are already given")
    draft.params = Some(compute)

  private[syntheke] def wrapperSpec(loc: SourceLoc): WrapperModuleSpec =
    val children = frame.wrapper.children.map(_.id.path.last).toVector ++
      session.imports.filter(_.spec.target.parent.contains(id)).map(_.spec.target.path.last)
    WrapperModuleSpec(id, frame.wrapper.moduleName, children, loc)

  private[syntheke] def generatorSpec(loc: SourceLoc): GeneratorModuleSpec =
    val draft   = frame.generator
    val nodes   = draft.nodes.toVector.map { entry =>
      val direction   = entry.draft match
        case _: InwardNodeDraft[?]  => NodeDirection.Inward
        case _: OutwardNodeDraft[?] => NodeDirection.Outward
      val computation = entry.computation.getOrElse(
        fail(s"node ${entry.draft.id.show} has no parameter: call fixed or derive", entry.loc)
      )
      NodeSpec(entry.draft.id.name, direction, entry.draft.protocol, computation, entry.draft.domains, entry.loc)
    }
    val compute = draft.params.getOrElse(fail(s"generator module ${id.show} never calls parameters(...)", loc))
    GeneratorModuleSpec(id, draft.definition, nodes, compute, loc, draft.probes.toVector)

  /** A boundary at `node`: a node of the root's boundary module, in `domains` and with `externalParams`, stands for the
    * outside and binds to it.
    */
  private def declareBoundary[P <: Protocol](
    node:           Port[P],
    externalParams: Any,
    domains:        ModuleNodeId => Vector[Domain[?]],
    name:           String,
    loc:            SourceLoc
  ): Boundary[P] =
    requireOpen("design boundary", loc)
    check(id == session.rootId, loc)("boundaries belong to the design root")
    DeclaredName.check(name, "boundary name", loc)
    val externalId            = ModuleNodeId(session.boundaryId, name)
    val members               = domains(externalId)
    val (boundary, direction) = node match
      case inner: InwardPort[P]  =>
        val external = new OutwardPort(node.protocol, externalId, members)
        recordBind(external, inner, loc)
        (new InwardBoundary(external), NodeDirection.Outward)
      case inner: OutwardPort[P] =>
        val external = new InwardPort(node.protocol, externalId, members)
        recordBind(inner, external, loc)
        (new OutwardBoundary(external), NodeDirection.Inward)
    session.registerBoundary(
      NodeSpec(name, direction, node.protocol, NodeComputation.constant(externalParams), members, loc),
      boundary
    )
    boundary

  private[syntheke] def boundary[P <: Protocol](
    node:            Port[P],
    externalParams:  Any,
    externalDomains: Seq[Domain[?]],
    name:            String,
    loc:             SourceLoc
  ): Boundary[P] =
    declareBoundary(node, externalParams, domainsOf(node.protocol, _, externalDomains, loc), name, loc)

  private[syntheke] def forwardBoundary[P <: Protocol](boundary: Boundary[P], name: String, loc: SourceLoc)
    : Boundary[P] =
    val imported = session.importOf(boundary, loc)
    val edge     = imported.edge(boundary, loc)
    val outside  = boundary match
      case _: OutwardBoundary[?] => edge.up
      case _: InwardBoundary[?]  => edge.down
    declareBoundary(imported.port(boundary).asInstanceOf[Port[P]], outside, _ => imported.domains(boundary), name, loc)

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
