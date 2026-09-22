package me.jiuyang.syntheke

import scala.collection.mutable
import upickle.default.Writer


private[syntheke] final class DomainEnv private (
  private val bindings: Map[DomainKey, DomainSelectorSpec.Contextual]):

  def updated(binding: DomainSelectorSpec.Contextual): DomainEnv =
    val DomainSelectorSpec.Contextual(handle, _) = binding
    new DomainEnv(bindings.updated(handle.domain.key, binding))

  def binding(domain: Domain): Option[DomainSelectorSpec.Contextual] =
    bindings.get(domain.key).map { case binding @ DomainSelectorSpec.Contextual(handle, _) =>
      require(
        handle.domain eq domain,
        s"domain key ${domain.key.show} is represented by two different definitions"
      )
      binding
    }

private[syntheke] object DomainEnv:
  val empty: DomainEnv = new DomainEnv(Map.empty)

private enum FrameLifecycle:
  case Active
  case Committed(spec: ModuleSpec)
  case Discarded

private sealed trait FrameDraft

private final class WrapperDraft(val moduleName: String) extends FrameDraft:
  val children = mutable.ArrayBuffer.empty[BuildFrame]
  val binds    = mutable.ArrayBuffer.empty[BindDecl]

private final case class NodeEntry(
  draft: NodeDraft[?],
  domains: Vector[NodeDomainSpec],
  loc: (sourcecode.File, sourcecode.Line),
  computation: Option[NodeComputation])

private final class GeneratorDraft(val definition: GeneratorDefinition[?]) extends FrameDraft:
  var nodes  = Vector.empty[NodeEntry]
  val probes = mutable.ArrayBuffer.empty[ProbeSpec[?]]
  val deps   = mutable.ArrayBuffer.empty[ParamDependencySpec]
  var params: Option[(EdgeView, DomainView) => Either[Violation, Any]] = None

  def seal(index: Int, computation: NodeComputation): Unit =
    nodes = nodes.updated(index, nodes(index).copy(computation = Some(computation)))

private final class BuildFrame(
  val session: BuildSession,
  val id:      ModuleId,
  val parent:  Option[BuildFrame],
  val draft: FrameDraft):

  private var lifecycle: FrameLifecycle = FrameLifecycle.Active

  val domainDecls        = mutable.ArrayBuffer.empty[DomainHandle[?]]
  val constraints        = mutable.ArrayBuffer.empty[Constraint]

  def isActive:    Boolean = lifecycle == FrameLifecycle.Active
  def isCommitted: Boolean = lifecycle match
    case FrameLifecycle.Committed(_) => true
    case _                           => false
  def isDiscarded: Boolean = lifecycle == FrameLifecycle.Discarded

  def wrapper: WrapperDraft = draft match
    case value: WrapperDraft => value
    case _ => throw new IllegalStateException(s"${id.show} is a generator module")

  def generator: GeneratorDraft = draft match
    case value: GeneratorDraft => value
    case _ => throw new IllegalStateException(s"${id.show} is a wrapper module")

  def commit(spec: ModuleSpec): Unit =
    require(isActive, s"module ${id.show} is not active")
    require(spec.id == id, s"module spec ${spec.id.show} does not belong to frame ${id.show}")
    lifecycle = FrameLifecycle.Committed(spec)

  def discard(): Unit = if isActive then lifecycle = FrameLifecycle.Discarded

  def spec: ModuleSpec = lifecycle match
    case FrameLifecycle.Committed(spec) => spec
    case _ => throw new IllegalArgumentException(s"requirement failed: module ${id.show} was not committed")

private[syntheke] final class BuildSession(val rootId: ModuleId):
  val boundaryId: ModuleId = rootId / "$boundary"
  private val boundaryNodes = mutable.ArrayBuffer.empty[NodeSpec]
  private val boundaryPorts = mutable.ArrayBuffer.empty[Port[?]]
  private val boundaries = mutable.ArrayBuffer.empty[Boundary[?]]
  private val imported = mutable.ArrayBuffer.empty[(BuildFrame, BoundaryImport)]
  private val probeExports = mutable.ArrayBuffer.empty[ResolvedProbe[?]]
  def imports: Vector[BoundaryImport] = imported.collect { case (frame, boundary) if live(frame) => boundary }.toVector
  def importOf(boundary: Boundary[?]): BoundaryImport =
    imports.find(_.contains(boundary)).getOrElse(throw IllegalArgumentException(s"boundary ${boundary.id.show} is not a public boundary of an instantiated design"))
  def instantiate[A](parent: BuildFrame, design: Design[A], id: ModuleId): DesignInstance[A] =
    val (definition, publicPorts) = design.frozen
    val placement = new DesignPlacement(definition, id)
    val resolved = placement.resolved
    val ports = design.dangles.place(publicPorts, placement)
    imported += parent -> new BoundaryImport(resolved, definition, this)
    new DesignInstance(ports, resolved)
  def forwardProbe[P](probe: ResolvedProbe[P]): ResolvedProbe[P] =
    require(imports.exists(_.design.probes.nodes.exists(_ eq probe)), s"Probe ${probe.id.show} is not public in an instantiated design")
    probeExports += probe
    probe
  def resolve(spec: DesignSpec): ResolvedDesign =
    val catalog = imports.map(_.design.probes).reduceOption(_.combined(_))
    val local = Negotiator.resolve(spec, catalog)
    imports.foreach(_.validate(local))
    val publicProbes = local.probes.published(spec.owner, probeExports.toVector)
    DesignIntegration.combine(imports.map(_.design), local.copy(probes = publicProbes,
      dependencies = imports.map(i => i.design.spec.root -> i.definition)))
  def registerBoundary(node: NodeSpec, port: Port[?], boundary: Boundary[?]): Unit =
    require(!boundaryNodes.exists(_.name == node.name), s"duplicate boundary '${node.name}'")
    boundaryNodes += node.copy(order = boundaryNodes.size)
    boundaryPorts += port
    boundaries += boundary

  private enum SessionLifecycle:
    case Building, Frozen, Aborted

  private var lifecycle: SessionLifecycle   = SessionLifecycle.Building
  private var root:      Option[BuildFrame] = None
  private val stack     = mutable.ArrayBuffer.empty[BuildFrame]
  private val allFrames = mutable.ArrayBuffer.empty[BuildFrame]

  private var nextBindOrder              = 0
  private var nextDomainDeclOrder        = 0

  private[syntheke] val owner = new DesignOwner

  def allocateBindOrder(): Int =
    val value = nextBindOrder
    nextBindOrder += 1
    value

  def allocateDomainDeclOrder(): Int =
    val value = nextDomainDeclOrder
    nextDomainDeclOrder += 1
    value

  private def newFrame(id: ModuleId, parent: Option[BuildFrame], draft: FrameDraft): BuildFrame =
    val frame = new BuildFrame(this, id, parent, draft)
    allFrames += frame
    frame

  private def enter(frame: BuildFrame): Unit =
    require(lifecycle == SessionLifecycle.Building, "Design build is no longer active")
    require(frame.isActive, s"module ${frame.id.show} is not active")
    frame.parent match
      case None         => require(stack.isEmpty, "cannot enter a second root module")
      case Some(parent) => require(stack.lastOption.contains(parent), s"parent ${parent.id.show} is not current")
    stack += frame

  private def leave(frame: BuildFrame): Unit =
    require(stack.lastOption.contains(frame), s"module ${frame.id.show} is not the current build frame")
    stack.dropRightInPlace(1)

  def requireCurrent(frame: BuildFrame, what: String): Unit =
    require(lifecycle == SessionLifecycle.Building, s"$what after Design build was frozen")
    require(frame.isActive, s"$what outside module ${frame.id.show}'s builder scope")
    require(stack.lastOption.contains(frame), s"$what while ${frame.id.show} is not the current module")

  private def live(frame: BuildFrame): Boolean = !frame.isDiscarded && frame.parent.forall(live)

  private def nodeDrafts(frame: BuildFrame): Iterator[NodeDraft[?]] =
    frame.draft match
      case generator: GeneratorDraft => generator.nodes.iterator.map(_.draft)
      case _:         WrapperDraft   => Iterator.empty

  def requireLiveNode(
    id:         ModuleNodeId,
    capability: NodeCapability,
    nodeOwner:  DesignOwner,
    role:       String
  ): NodeHandle[?] =
    require(nodeOwner eq owner, s"$role ${id.show} belongs to another Design build")
    val found = (allFrames.iterator.filter(live).flatMap(nodeDrafts) ++ boundaryPorts.iterator ++ imports.iterator.flatMap(_.ports.values)).find(_.capability eq capability)
    require(
      found.isDefined,
      s"$role ${id.show} is not the live incarnation of a declared node in this Design build"
    )
    require(found.get.id == id, s"$role ${id.show} has a mismatched node identity")
    found.get

  def requireLiveNode(node: NodeHandle[?], role: String): Unit =
    val declared = requireLiveNode(node.id, node.capability, node.owner, role)
    require(declared.protocol eq node.protocol, s"$role ${node.id.show} has a mismatched protocol object")

  def requireLiveReader(reader: Reader[?], role: String): Unit =
    requireLiveNode(reader.node, reader.nodeCapability, reader.owner0, role)

  def requireLiveDomain(handle: DomainHandle[?], role: String): Unit =
    require(handle.owner eq owner, s"$role ${handle.id.show} belongs to another Design build")
    require(
      allFrames.exists(frame => live(frame) && frame.domainDecls.exists(_ eq handle)) || imports.exists(_.domains.exists(_ eq handle)),
      s"$role ${handle.id.show} is not the live incarnation of a domain declaration in this Design build"
    )

  def requireLiveNodeDomain(use: NodeDomain[?], role: String): Unit =
    require(use.owner eq owner, s"$role ${use.key.show} belongs to another Design build")
    require(
      boundaryPorts.exists(_.nodeDomains.exists(_ eq use)) || imports.exists(_.ports.values.exists(_.nodeDomains.exists(_ eq use))) || allFrames.exists { frame =>
        live(frame) && (frame.draft match
          case generator: GeneratorDraft => generator.nodes.exists(_.domains.exists(_.capability eq use))
          case _:         WrapperDraft   => false)
      },
      s"$role ${use.key.show} is not the live incarnation of a declared domain use in this Design build"
    )

  private[syntheke] def build[A](
    rootModuleName: String,
    loc:            (sourcecode.File, sourcecode.Line)
  )(body:           WrapperScope ?=> (A, Vector[Constraint])
  ): (DesignSpec, A) =
    require(root.isEmpty, "a BuildSession can build only one Design")
    DeclaredName.require(rootModuleName, "root wrapper module name")
    val rootFrame   = newFrame(rootId, None, new WrapperDraft(rootModuleName))
    root = Some(rootFrame)
    val rootContext = new BuildContext[WrapperMode](rootFrame, DomainEnv.empty)
    enter(rootFrame)
    val result = try
      val (result, constraints) = body(
        using rootContext
      )
      rootContext.acceptConstraints(constraints)
      rootFrame.commit(rootContext.wrapperSpec(loc))
      result
    finally
      if !rootFrame.isCommitted then
        rootFrame.discard()
        lifecycle = SessionLifecycle.Aborted
      leave(rootFrame)
    (freeze(), result)

  private def freeze(): DesignSpec =
    require(lifecycle == SessionLifecycle.Building, "BuildSession is not ready to freeze")
    require(stack.isEmpty, "cannot freeze while a module body is active")
    val rootFrame = root.getOrElse(throw new IllegalStateException("root module was never built"))
    require(rootFrame.isCommitted, "root module was not committed")

    def preorder(frame: BuildFrame): Vector[BuildFrame] =
      frame +: (frame.draft match
        case wrapper: WrapperDraft   => wrapper.children.toVector.flatMap(preorder)
        case _:       GeneratorDraft => Vector.empty)

    val frames = preorder(rootFrame)
    require(frames.forall(_.isCommitted), "a reachable module frame was not committed")
    lifecycle = SessionLifecycle.Frozen

    val binds              = frames.flatMap {
      _.draft match
        case wrapper: WrapperDraft   => wrapper.binds
        case _:       GeneratorDraft => Vector.empty
    }.sortBy(_.order).zipWithIndex.map((decl, order) => decl.copy(order = order))
    val domainDecls        =
      frames
        .flatMap(_.domainDecls)
        .sortBy(_.order)
    val constraints = frames
      .flatMap(frame => frame.constraints.map(DomainContributor.Module(frame.id) -> _))
      .zipWithIndex
      .map { case ((source, constraint), order) => ConstraintSpec(source, constraint, order) }

    DesignSpec(
      owner = owner,
      modules = frames.map(frame => frame.id -> frame.spec).toMap ++
        Option.when(boundaryNodes.nonEmpty)(boundaryId -> BoundaryModuleSpec(boundaryId, rootId, false, boundaryNodes.toVector, rootFrame.spec.loc)) ++
        imports.map(i => i.spec.id -> i.spec),
      moduleOrder = frames.map(_.id) ++ Option.when(boundaryNodes.nonEmpty)(boundaryId) ++ imports.map(_.spec.id),
      binds = binds,
      domainDecls = domainDecls ++ imports.flatMap(_.domains),
      constraints = constraints ++ imports.flatMap(_.constraints),
      root = rootId,
      boundaries = boundaries.toVector
    )

  def childWrapper(parent: BuildFrame, id: ModuleId, moduleName: String): BuildFrame =
    newFrame(id, Some(parent), new WrapperDraft(moduleName))

  def childGenerator[FP](parent: BuildFrame, id: ModuleId, definition: GeneratorDefinition[FP]): BuildFrame =
    newFrame(id, Some(parent), new GeneratorDraft(definition))

  def runChild[R <: BuildMode, A](
    parent:  BuildFrame,
    child:   BuildFrame,
    context: BuildContext[R]
  )(body:    BuildContext[R] ?=> (A, Vector[Constraint])
  )(close:   => ModuleSpec
  ): A =
    enter(child)
    val result    =
      try
        val (value, constraints) = body(
          using context
        )
        context.acceptConstraints(constraints)
        child.commit(close)
        value
      finally
        if !child.isCommitted then child.discard()
        leave(child)
    requireCurrent(parent, s"commit child ${child.id.show}")
    parent.wrapper.children += child
    result

final class BuildContext[+R <: BuildMode] private[syntheke] (
  private val frame: BuildFrame,
  private val domainEnv: DomainEnv):

  private[syntheke] val id:    ModuleId    = frame.id
  private[syntheke] def owner: DesignOwner = frame.session.owner

  private def session:                   BuildSession = frame.session
  private def requireOpen(what: String): Unit         = session.requireCurrent(frame, what)

  private def requireReadable[D <: Domain](token: DomainReadable[D], role: String): Unit =
    token match
      case handle: DomainHandle[?] => session.requireLiveDomain(handle, role)
      case use: NodeDomain[?] =>
        session.requireLiveNodeDomain(use, role)
        require(use.module == id, s"$role belongs to ${use.module.show}, not ${id.show}")

  private[syntheke] def withDomain[D <: Domain, A](
    handle: DomainHandle[D]
  )(body: BuildContext[R] ?=> A
  ): A =
    requireOpen(s"provide domain ${handle.id.show}")
    session.requireLiveDomain(handle, "contextual domain")
    require(
      handle.domain.attachmentPolicy.permits(AttachmentMethod.Contextual),
      s"${handle.domain.key.show} does not permit Contextual attachments"
    )
    val nextEnv = domainEnv.updated(DomainSelectorSpec.Contextual(handle, id))
    body(
      using new BuildContext[R](frame, nextEnv)
    )

  private def domainBinding(domain: Domain): DomainSelectorSpec.Contextual =
    domainEnv
      .binding(domain)
      .getOrElse(
        throw new IllegalArgumentException(s"${id.show}: no contextual domain for ${domain.key.show}")
      )

  private[syntheke] def declareDomain[D <: Domain](
    domain: D,
    name:  String,
    sources: DomainReadable[?] | scala.collection.Seq[DomainReadable[?]],
    run:   DomainView => Either[Violation, (domain.Value, Option[domain.Requirement])],
    loc:   (sourcecode.File, sourcecode.Line)
  ): DomainHandle[D] =
    val reads: Vector[DomainReadable[?]] = sources match
      case source: DomainReadable[?]                        => Vector(source)
      case sources: scala.collection.Seq[DomainReadable[?]] => sources.toVector
    requireOpen("domain declaration")
    require(!frame.domainDecls.exists(_.id.name == name), s"duplicate domain '$name' in ${id.show}")
    reads.foreach(r => requireReadable(r, "domain read token"))
    val did    = DomainDeclId(id, name)
    val handle = new DomainHandle(
      domain,
      did,
      owner,
      reads.distinct,
      session.allocateDomainDeclOrder(),
      loc
    )(run)
    frame.domainDecls += handle
    handle

  private[syntheke] def acceptConstraints(
    constraints: Vector[Constraint]
  ): Unit =
    requireOpen("module constraints")
    constraints.foreach(_.reads.foreach {
      case handle: DomainHandle[?] => session.requireLiveDomain(handle, "constraint token")
      case node: NodeDomain[?] => session.requireLiveNodeDomain(node, "constraint token")
    })
    frame.constraints ++= constraints

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
    session.instantiate(frame, design, requireChildName(name))

  private[syntheke] def wrapper[A: Dangles](
    name:       String,
    moduleName: String
  )(body:       WrapperScope ?=> (A, Vector[Constraint])
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): A =
    DeclaredName.require(moduleName, s"wrapper module name at instance '$name' in ${id.show}")
    val childId = requireChildName(name)
    val child   = session.childWrapper(frame, childId, moduleName)
    val context = new BuildContext[WrapperMode](child, domainEnv)
    session.runChild(frame, child, context)(body)(context.wrapperSpec((file, line)))

  private[syntheke] def generator[FP, A: Dangles](
    name:       String,
    definition: GeneratorDefinition[FP]
  )(body:       GeneratorScope[FP] ?=> (A, Vector[Constraint])
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): A =
    val childId = requireChildName(name)
    val child   = session.childGenerator(frame, childId, definition)
    val context = new BuildContext[GeneratorMode[FP]](child, domainEnv)
    session.runChild(frame, child, context)(body)(context.generatorSpec((file, line)))

  private[syntheke] def recordBind(
    source: OutwardPort[?],
    target: InwardPort[?],
    loc:    (sourcecode.File, sourcecode.Line)
  ): Unit =
    requireOpen(s"bind ${source.id.show} -> ${target.id.show}")
    session.requireLiveNode(source, "bind source")
    session.requireLiveNode(target, "bind target")
    frame.wrapper.binds += BindDecl(session.allocateBindOrder(), source.id, target.id, id, loc)

  private def reserveName(name: String, draft: GeneratorDraft): Unit =
    requireOpen(s"declaration '$name'")
    DeclaredName.require(name, s"declaration name in ${id.show}")
    val taken = draft.nodes.exists(_.draft.id.name == name) || draft.probes.exists(_.node.id.name == name)
    require(!taken, s"duplicate declaration name '$name' in ${id.show}")

  private[syntheke] def declareProbe[FP, P: TypeIdentity: Writer](
    selector: ProbeSelector[FP, P]
  )(name: String, loc: (sourcecode.File, sourcecode.Line)): ProbeNode[P] =
    val draft = frame.generator
    reserveName(name, draft)
    val node = new ProbeNode[P](ModuleNodeId(id, name), owner)
    draft.probes += new ProbeSpec(
      node,
      (parameter, declaration) => selector.resolve(parameter.asInstanceOf[FP], declaration),
      loc
    )
    node

  private def requireDraft(node: NodeDraft[?], draft: GeneratorDraft): Int =
    requireOpen(s"node declaration ${node.id.show}")
    val index = draft.nodes.indexWhere(_.draft eq node)
    require(
      node.id.module == id && index >= 0,
      s"node ${node.id.show} is not a draft of ${id.show}"
    )
    require(draft.nodes(index).computation.isEmpty, s"node ${node.id.show} is already sealed")
    index

  private[syntheke] def inward(
    p: Protocol
  )(domains: Seq[Domain | DomainReadable[?]]
  )(name: String
  )(
    using file: sourcecode.File,
    line: sourcecode.Line
  ): p.InwardDraft =
    val draft = frame.generator
    reserveName(name, draft)
    val scope = this.asInstanceOf[BuildContext[? <: GeneratorMode[?]]]
    val nodeId = ModuleNodeId(id, name)
    val uses = nodeDomains(p, nodeId, NodeDirection.Inward, domains, (file, line))
    val b = new InwardNodeDraft[p.type](p, scope, nodeId, new NodeCapability, uses.map(_.capability))
    draft.nodes = draft.nodes :+ NodeEntry(b, uses, (file, line), None)
    b

  private[syntheke] def outward(
    p: Protocol
  )(domains: Seq[Domain | DomainReadable[?]]
  )(name: String
  )(
    using file: sourcecode.File,
    line: sourcecode.Line
  ): p.OutwardDraft =
    val draft = frame.generator
    reserveName(name, draft)
    val scope = this.asInstanceOf[BuildContext[? <: GeneratorMode[?]]]
    val nodeId = ModuleNodeId(id, name)
    val uses = nodeDomains(p, nodeId, NodeDirection.Outward, domains, (file, line))
    val b = new OutwardNodeDraft[p.type](p, scope, nodeId, new NodeCapability, uses.map(_.capability))
    draft.nodes = draft.nodes :+ NodeEntry(b, uses, (file, line), None)
    b

  private def nodeDomains(
    protocol: Protocol,
    node: ModuleNodeId,
    direction: NodeDirection,
    domains: Seq[Domain | DomainReadable[?]],
    loc: (sourcecode.File, sourcecode.Line)
  ): Vector[NodeDomainSpec] =
    def definition(source: Domain | DomainReadable[?]): Domain = source match
      case domain: Domain => domain
      case token: DomainReadable[?] => token.domain

    val bindings = domains.toVector
    require(
      bindings.map(definition(_).key).distinct.size == bindings.size,
      s"node ${node.show} repeats a domain definition"
    )
    protocol.carries.foreach { domain =>
      require(
        bindings.exists(entry => definition(entry) eq domain),
        s"node ${node.show} is missing carried domain ${domain.key.show}"
      )
    }
    val firstOrder = frame.draft match
      case generator: GeneratorDraft => generator.nodes.iterator.map(_.domains.size).sum
      case _: WrapperDraft => 0
    bindings.zipWithIndex.map { case (source, index) =>
      val domain = definition(source)
      val key = NodeDomainKey(node, domain.key)
      val carried = protocol.carries.exists(_ eq domain)
      val selector = (carried, direction, source) match
        case (true, NodeDirection.Inward, _: Domain) => DomainSelectorSpec.CarrierIn
        case (true, NodeDirection.Inward, _: DomainReadable[?]) =>
          throw new IllegalArgumentException(s"${key.show} must receive its carried domain through its input connection")
        case (true, NodeDirection.Outward, source: DomainReadable[?]) =>
          DomainSelectorSpec.CarrierOut(source)
        case (true, NodeDirection.Outward, _) =>
          throw new IllegalArgumentException(s"${key.show} must specify a domain source for its carried output")
        case (false, _, _: Domain) => domainBinding(domain)
        case (false, _, handle: DomainHandle[?]) => DomainSelectorSpec.Direct(handle)
        case (false, _, use: NodeDomain[?]) => DomainSelectorSpec.Follow(use)
      validateDomainSelector(key, domain, selector)
      val token = new NodeDomain(domain, key, owner)
      NodeDomainSpec(key, domain, selector, firstOrder + index, loc, token)
    }

  private def validateDomainSelector(
    key: NodeDomainKey,
    domain: Domain,
    selector: DomainSelectorSpec
  ): Unit =
    def requireDomain(handle: DomainHandle[?]): Unit =
      session.requireLiveDomain(handle, "domain selector")
      require(handle.domain eq domain, s"domain ${handle.id.show} has a different definition from ${key.show}")

    def requireFollow(use: NodeDomain[?]): Unit =
      session.requireLiveNodeDomain(use, "follow target")
      require(use.domain eq domain, s"follow target ${use.key.show} has a different domain definition")
      require(use.key.node != key.node, s"${key.show} cannot directly follow itself")

    selector match
      case _: DomainSelectorSpec.Frozen => throw IllegalArgumentException("frozen domains cannot be declared by a builder")
      case DomainSelectorSpec.Direct(handle) => requireDomain(handle)
      case DomainSelectorSpec.Contextual(handle, providedAt) =>
        requireDomain(handle)
        require(providedAt.isAncestorOf(id), s"contextual provider ${providedAt.show} is not an ancestor of ${id.show}")
      case DomainSelectorSpec.CarrierOut(source) => source match
        case handle: DomainHandle[?] => requireDomain(handle)
        case use: NodeDomain[?] => requireFollow(use)
      case DomainSelectorSpec.Follow(use) => requireFollow(use)
      case DomainSelectorSpec.CarrierIn                                   => ()

  private def sealDraft(
    node: NodeDraft[?],
    plan: ReadPlan,
    f:    ReadValues => Either[Violation, (Any, Vector[Constraint])]
  ): Unit =
    val draft = frame.generator
    val index = requireDraft(node, draft)
    plan.tokens.foreach { token =>
      require(token.tokenOwner eq owner, s"read-plan token of ${node.id.show} belongs to another Design build")
      token match
        case r: DownReader[?]    =>
          session.requireLiveReader(r, "DownReader")
          require(r.node.module == id, s"${node.id.show}: DownReader target ${r.node.show} is not local to ${id.show}")
          require(
            node.isInstanceOf[OutwardNodeDraft[?]],
            s"${node.id.show}: a DownReader belongs only in an outward node plan"
          )
        case r: UpReader[?]      =>
          session.requireLiveReader(r, "UpReader")
          require(r.node.module == id, s"${node.id.show}: UpReader target ${r.node.show} is not local to ${id.show}")
          require(
            node.isInstanceOf[InwardNodeDraft[?]],
            s"${node.id.show}: an UpReader belongs only in an inward node plan"
          )
        case u: NodeDomain[?] =>
          require(u.key.node == node.id, s"${node.id.show}: domain token ${u.key.show} belongs to another node")
          require(draft.nodes(index).domains.exists(_.capability eq u), s"${node.id.show}: unregistered domain token ${u.key.show}")
    }
    val dependencies = plan.tokens.collect {
      case r: DownReader[?] => r.node.name -> node.id.name
      case r: UpReader[?] => node.id.name -> r.node.name
    }.distinct.filterNot((from, to) => draft.deps.exists(d => d.from == from && d.to == to))
    val offset = draft.deps.size
    draft.deps ++= dependencies.zipWithIndex.map { case ((from, to), order) =>
      ParamDependencySpec(from, to, offset + order, draft.nodes(index).loc)
    }
    draft.seal(index, NodeComputation.Derived(plan, f))

  private[syntheke] def fixed[P <: Protocol](node: InwardNodeDraft[P], value: Any): InwardPort[P] =
    val draft = frame.generator
    val index = requireDraft(node, draft)
    draft.seal(index, NodeComputation.Constant(value))
    new InwardPort(node.protocol, node.id, owner, node.capability, node.nodeDomains)

  private[syntheke] def fixed[P <: Protocol](node: OutwardNodeDraft[P], value: Any): OutwardPort[P] =
    val draft = frame.generator
    val index = requireDraft(node, draft)
    draft.seal(index, NodeComputation.Constant(value))
    new OutwardPort(node.protocol, node.id, owner, node.capability, node.nodeDomains)

  private[syntheke] def seal[P <: Protocol](
    node: InwardNodeDraft[P],
    plan: ReadPlan,
    f:    ReadValues => Either[Violation, (Any, Vector[Constraint])]
  ): InwardPort[P] =
    sealDraft(node, plan, f)
    new InwardPort(node.protocol, node.id, owner, node.capability, node.nodeDomains)

  private[syntheke] def seal[P <: Protocol](
    node: OutwardNodeDraft[P],
    plan: ReadPlan,
    f:    ReadValues => Either[Violation, (Any, Vector[Constraint])]
  ): OutwardPort[P] =
    sealDraft(node, plan, f)
    new OutwardPort(node.protocol, node.id, owner, node.capability, node.nodeDomains)

  private[syntheke] def parameters[FP](compute: (EdgeView, DomainView) => Either[Violation, FP]): Unit =
    val draft = frame.generator
    requireOpen(s"parameters of ${id.show}")
    require(draft.params.isEmpty, s"parameters of ${id.show} already set")
    draft.params = Some(compute)

  private[syntheke] def wrapperSpec(loc: (sourcecode.File, sourcecode.Line)): WrapperModuleSpec =
    requireOpen(s"close wrapper ${id.show}")
    val draft = frame.wrapper
    WrapperModuleSpec(id, draft.moduleName, draft.children.map(_.id.path.last).toVector ++
      session.imports.filter(_.spec.target.parent.contains(id)).map(_.spec.target.path.last), loc, new Object)

  private[syntheke] def generatorSpec(loc: (sourcecode.File, sourcecode.Line)): GeneratorModuleSpec =
    requireOpen(s"close generator ${id.show}")
    val draft     = frame.generator
    val nodeSpecs = draft.nodes.zipWithIndex.map { (entry, order) =>
      val builder = entry.draft
      val direction = builder match
        case _: InwardNodeDraft[?] => NodeDirection.Inward
        case _: OutwardNodeDraft[?] => NodeDirection.Outward
      val computation = entry.computation.getOrElse(
        throw new IllegalStateException(s"node ${builder.id.show}: draft was never sealed")
      )
      NodeSpec(
        name = builder.id.name,
        direction = direction,
        protocol = builder.protocol,
        computation = computation,
        nodeDomains = entry.domains,
        order = order,
        loc = entry.loc,
        capability = builder.capability
      )
    }
    val compute   = draft.params.getOrElse(
      throw new IllegalStateException(s"generator module ${id.show}: parameters(...) is mandatory but was never set")
    )
    GeneratorModuleSpec(id, draft.definition, nodeSpecs, draft.deps.toVector, compute, loc, draft.probes.toVector)

  private def declareBoundary[P <: Protocol](
    node: Port[P], externalParams: Any, domainSpecs: ModuleNodeId => Vector[NodeDomainSpec], name: String,
    externalDirection: NodeDirection, loc: (sourcecode.File, sourcecode.Line)
  ): Boundary[P] =
    requireOpen("design boundary")
    require(id == session.rootId, "boundaries belong to the design root")
    session.requireLiveNode(node, "boundary source")
    DeclaredName.require(name, "boundary name")
    val externalId = ModuleNodeId(session.boundaryId, name)
    val domains = domainSpecs(externalId)
    val capability = new NodeCapability
    val externalPort = externalDirection match
      case NodeDirection.Outward => new OutwardPort(node.protocol, externalId, owner, capability, domains.map(_.capability))
      case NodeDirection.Inward => new InwardPort(node.protocol, externalId, owner, capability, domains.map(_.capability))
    val publicId = ModuleNodeId(id, name)
    val boundary = node match
      case _: InwardPort[P] => new InwardBoundary(node.protocol, publicId, externalPort.asInstanceOf[OutwardPort[P]])
      case _: OutwardPort[P] => new OutwardBoundary(node.protocol, publicId, externalPort.asInstanceOf[InwardPort[P]])
    session.registerBoundary(NodeSpec(name, externalDirection, node.protocol, NodeComputation.Constant(externalParams), domains, 0, loc, capability), externalPort, boundary)
    externalDirection match
      case NodeDirection.Outward => recordBind(externalPort.asInstanceOf[OutwardPort[P]], node.asInstanceOf[InwardPort[P]], loc)
      case NodeDirection.Inward => recordBind(node.asInstanceOf[OutwardPort[P]], externalPort.asInstanceOf[InwardPort[P]], loc)
    boundary

  private[syntheke] def inwardBoundary[P <: Protocol](
    node: InwardPort[P], externalParams: Any, externalDomains: Seq[Domain | DomainReadable[?]], name: String,
    loc: (sourcecode.File, sourcecode.Line)
  ): InwardBoundary[P] =
    declareBoundary(node, externalParams, id => nodeDomains(node.protocol, id, NodeDirection.Outward, externalDomains, loc),
      name, NodeDirection.Outward, loc).asInstanceOf[InwardBoundary[P]]

  private[syntheke] def outwardBoundary[P <: Protocol](
    node: OutwardPort[P], externalParams: Any, externalDomains: Seq[Domain | DomainReadable[?]], name: String,
    loc: (sourcecode.File, sourcecode.Line)
  ): OutwardBoundary[P] =
    declareBoundary(node, externalParams, id => nodeDomains(node.protocol, id, NodeDirection.Inward, externalDomains, loc),
      name, NodeDirection.Inward, loc).asInstanceOf[OutwardBoundary[P]]

  private[syntheke] def forwardBoundary[P <: Protocol](
    boundary: Boundary[P], name: String, loc: (sourcecode.File, sourcecode.Line)
  ): Boundary[P] =
    val imported = session.importOf(boundary)
    val node = imported.port(boundary).asInstanceOf[Port[P]]
    val edge = imported.design.boundaryEdge(boundary)
    val outward = boundary.isInstanceOf[OutwardBoundary[?]]
    val direction = if outward then NodeDirection.Inward else NodeDirection.Outward
    declareBoundary(node, if outward then edge.up else edge.down,
      id => imported.forwardDomains(boundary, id, direction, loc), name, direction, loc)

  private[syntheke] def forwardProbe[P](probe: ResolvedProbe[P]): ResolvedProbe[P] =
    requireOpen("public Probe boundary")
    require(id == session.rootId, "Probe boundaries belong to the design root")
    session.forwardProbe(probe)

  private[syntheke] def connectBoundaries[P <: Protocol](
    source: OutwardBoundary[P], target: InwardBoundary[P], loc: (sourcecode.File, sourcecode.Line)
  ): Unit =
    recordBind(session.importOf(source).port(source).asInstanceOf[OutwardPort[P]],
      session.importOf(target).port(target).asInstanceOf[InwardPort[P]], loc)

  private[syntheke] def connectBoundary[P <: Protocol](
    boundary: Boundary[P], node: Port[P], loc: (sourcecode.File, sourcecode.Line)
  ): Unit =
    val imported = session.importOf(boundary)
    val peer = imported.port(boundary)
    require(peer.protocol eq node.protocol, "boundary protocol mismatch")
    (peer, node) match
      case (source: OutwardPort[?], target: InwardPort[?]) => recordBind(source, target, loc)
      case (target: InwardPort[?], source: OutwardPort[?]) => recordBind(source, target, loc)
      case _ => throw IllegalArgumentException("boundary direction mismatch")

  private[syntheke] def boundaryDomain[D <: Domain](boundary: Boundary[?], domain: D): NodeDomain[D] =
    requireOpen("read boundary domain")
    session.importOf(boundary).port(boundary).lookupDomain(domain)
