// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

import scala.collection.mutable

/** The Build phase (doc @sec-build).
  *
  * `Design { ... }` opens the root wrapper scope; wrapper scopes instantiate child modules and record binds; generator
  * scopes declare nodes, module-internal parameter dependencies and verification endpoints. When the design body
  * returns, the builder freezes into an immutable [[DesignSpec]].
  *
  * The bind operator is written `target <-- source` (`<-` itself is reserved by Scala).
  */
object Design:
  def apply(
    body:      WrapperScope ?=> Unit
  )(
    using loc: SourceLocation
  ): DesignSpec =
    val st    = new BuildState
    val scope = new WrapperScope(ModuleId.root, st)
    st.moduleOrder += ModuleId.root
    body(
      using scope
    )
    scope.close(loc)
    DesignSpec(
      modules = st.modules.toMap,
      moduleOrder = st.moduleOrder.toVector,
      binds = st.binds.toVector,
      dvBinds = st.dvBinds.toVector,
      protocols = st.protocols.toVector,
      generators = st.generators.toVector
    )

private final class BuildState:
  val modules     = mutable.Map.empty[ModuleId, ModuleSpec]
  val moduleOrder = mutable.ArrayBuffer.empty[ModuleId]
  val binds       = mutable.ArrayBuffer.empty[BindDecl]
  val dvBinds     = mutable.ArrayBuffer.empty[DVBindDecl]
  val protocols   = mutable.ArrayBuffer.empty[(ProtocolId, AnyRef)]
  val generators  = mutable.ArrayBuffer.empty[GeneratorEntry[?]]

  def registerProtocol(id: ProtocolId, p: AnyRef): Unit =
    if !protocols.exists((i, o) => i == id && (o eq p)) then protocols += (id -> p)
  def registerGenerator(e: GeneratorEntry[?]):     Unit =
    if !generators.exists(_ eq e) then generators += e

/** Read handles produced by a dependency declaration; the only way a port parameter function reads a peer node. */
final class DownReader[T] private[syntheke] (private[syntheke] val node: ModuleNodeId)
final class UpReader[T] private[syntheke] (private[syntheke] val node: ModuleNodeId)

private[syntheke] final class UndeclaredReadException(val node: ModuleNodeId)
    extends RuntimeException(s"read of ${node.show} which is not a declared dependency of this function")

/** Values visible to one port parameter function evaluation. */
final class ReadCtx private[syntheke] (values: Map[ModuleNodeId, Any]):
  def apply[T](r: DownReader[T]): T =
    values.getOrElse(r.node, throw new UndeclaredReadException(r.node)).asInstanceOf[T]
  def apply[T](r: UpReader[T]):   T =
    values.getOrElse(r.node, throw new UndeclaredReadException(r.node)).asInstanceOf[T]

sealed trait NodeBuilder[P <: Protocol]:
  val protocol: P
  def id:                         ModuleNodeId
  private[syntheke] def moduleId: ModuleId = id.module
  private[syntheke] val refDecls = mutable.ArrayBuffer.empty[CrossProtocolRefSpec]

  /** Declare a cross-protocol reference to another node of the same module (clock / power domain). */
  def ref(
    name:      String,
    target:    NodeBuilder[?]
  )(
    using loc: SourceLocation
  ): this.type =
    refDecls += CrossProtocolRefSpec(name, target.id, target.protocol.id, loc)
    this

/** An inward node under declaration; carries the mandatory uFn. */
final class InwardNodeBuilder[P <: Protocol] private[syntheke] (
  val protocol: P,
  val id:       ModuleNodeId,
  val order:    Int,
  val loc:      SourceLocation)
    extends NodeBuilder[P]:
  private[syntheke] var fn:                                         Option[ReadCtx => Either[PropagationViolation, Any]] = None
  def uFn(f: ReadCtx => Either[PropagationViolation, protocol.Up]): this.type                                            =
    require(fn.isEmpty, s"uFn of ${id.show} already set")
    fn = Some(f)
    this

/** An outward node under declaration; carries the mandatory dFn. */
final class OutwardNodeBuilder[P <: Protocol] private[syntheke] (
  val protocol: P,
  val id:       ModuleNodeId,
  val order:    Int,
  val loc:      SourceLocation)
    extends NodeBuilder[P]:
  private[syntheke] var fn:                                           Option[ReadCtx => Either[PropagationViolation, Any]] = None
  def dFn(f: ReadCtx => Either[PropagationViolation, protocol.Down]): this.type                                            =
    require(fn.isEmpty, s"dFn of ${id.show} already set")
    fn = Some(f)
    this

/** Verification endpoint handles. */
final class DVSourceRef[P <: DVProtocol] private[syntheke] (val protocol: P, val id: DVSourceId)
final class DVSinkRef[P <: DVProtocol] private[syntheke] (val protocol: P, val id: DVSinkId)

/** Design bind: `target <-- source`, recorded in the enclosing wrapper scope. Same protocol type on both ends. */
extension [P <: Protocol](target: InwardNodeBuilder[P])
  infix def <--(
    source:   OutwardNodeBuilder[P]
  )(
    using ws: WrapperScope,
    loc:      SourceLocation
  ): Unit =
    ws.recordBind(source.id, target.id, loc)

/** Verification bind: `sink <-- source`. */
extension [P <: DVProtocol](sink: DVSinkRef[P])
  infix def <--(
    source:   DVSourceRef[P]
  )(
    using ws: WrapperScope,
    loc:      SourceLocation
  ): Unit =
    ws.recordDVBind(source.id, sink.id, loc)

/** A structural module under construction. */
final class WrapperScope private[syntheke] (val id: ModuleId, st: BuildState):
  private val children = mutable.ArrayBuffer.empty[String]

  /** Instantiate a child wrapper module. */
  def wrapper(
    name:      String
  )(body:      WrapperScope ?=> Unit
  )(
    using loc: SourceLocation
  ): ModuleId =
    val childId = id / name
    children += name
    st.moduleOrder += childId
    val scope   = new WrapperScope(childId, st)
    body(
      using scope
    )
    scope.close(loc)
    childId

  /** Instantiate a child generator module bound to a registry entry. */
  def generator[FP](
    name:  String,
    entry: GeneratorEntry[FP]
  )(body:  GeneratorScope[FP] ?=> Unit
  )(
    using
    loc:   SourceLocation
  ): ModuleId =
    val childId = id / name
    children += name
    st.moduleOrder += childId
    st.registerGenerator(entry)
    val scope   = new GeneratorScope[FP](childId, st, entry)
    body(
      using scope
    )
    scope.close(loc)
    childId

  private[syntheke] def recordBind(source: ModuleNodeId, target: ModuleNodeId, loc: SourceLocation): Unit =
    st.binds += BindDecl(st.binds.size, source, target, id, loc)
  private[syntheke] def recordDVBind(source: DVSourceId, sink: DVSinkId, loc: SourceLocation):       Unit =
    st.dvBinds += DVBindDecl(st.dvBinds.size, source, sink, id, loc)

  private[syntheke] def close(loc: SourceLocation): Unit =
    st.modules(id) = WrapperModuleSpec(id, children.toVector, loc)

/** A generator module under construction: nodes, dependencies, verification endpoints and parameter functions. */
final class GeneratorScope[FP] private[syntheke] (val id: ModuleId, st: BuildState, entry: GeneratorEntry[FP]):
  private val nodes  = mutable.ArrayBuffer.empty[NodeBuilder[?]]
  private val deps   = mutable.ArrayBuffer.empty[ParamDependencySpec]
  private val dvSrcs = mutable.ArrayBuffer.empty[DVSourceSpec]
  private val dvSnks = mutable.ArrayBuffer.empty[DVSinkSpec]
  private var params: Option[(EdgeView => Either[CapabilityViolation, Any], Any => Any)] = None

  /** Declare a named inward node of protocol `p`. */
  def inward(
    p:         Protocol
  )(name:      String
  )(
    using loc: SourceLocation
  ): InwardNodeBuilder[p.type] =
    st.registerProtocol(p.id, p)
    val b = new InwardNodeBuilder[p.type](p, ModuleNodeId(id, name), nodes.size, loc)
    nodes += b
    b

  /** Declare a named outward node of protocol `p`. */
  def outward(
    p:         Protocol
  )(name:      String
  )(
    using loc: SourceLocation
  ): OutwardNodeBuilder[p.type] =
    st.registerProtocol(p.id, p)
    val b = new OutwardNodeBuilder[p.type](p, ModuleNodeId(id, name), nodes.size, loc)
    nodes += b
    b

  /** Declare one module-internal parameter dependency and receive the two read handles it grants: the outward node's
    * dFn may read the inward node's `Down`, the inward node's uFn may read the outward node's `Up` (doc @sec-generator-module).
    */
  def depend(
    from:      InwardNodeBuilder[?],
    to:        OutwardNodeBuilder[?]
  )(
    using loc: SourceLocation
  ): (DownReader[from.protocol.Down], UpReader[to.protocol.Up]) =
    deps += ParamDependencySpec(from.id.name, to.id.name, deps.size, loc)
    (new DownReader[from.protocol.Down](from.id), new UpReader[to.protocol.Up](to.id))

  /** Declare a probe source providing its verification `Down` and FIRRTL layer path. */
  def dvSource(
    p:     DVProtocol
  )(name:  String,
    down:  p.Down,
    layer: LayerPath
  )(
    using
    loc:   SourceLocation
  ): DVSourceRef[p.type] =
    st.registerProtocol(p.id, p)
    dvSrcs += DVSourceSpec(name, p, down, layer, dvSrcs.size + dvSnks.size, loc)
    new DVSourceRef[p.type](p, DVSourceId(id, name))

  /** Declare a probe sink on a verification generator module. */
  def dvSink(
    p:         DVProtocol
  )(name:      String
  )(
    using loc: SourceLocation
  ): DVSinkRef[p.type] =
    st.registerProtocol(p.id, p)
    dvSnks += DVSinkSpec(name, p, dvSrcs.size + dvSnks.size, loc)
    new DVSinkRef[p.type](p, DVSinkId(id, name))

  /** Declare `computeProtocolParam` and `combine` (doc @sec-two-layer-params). */
  def parameters[PP](compute: EdgeView => Either[CapabilityViolation, PP])(combine: PP => FP): Unit =
    require(params.isEmpty, s"parameters of ${id.show} already set")
    params = Some((compute, pp => combine(pp.asInstanceOf[PP])))

  /** A generator whose full parameter ignores the negotiation result entirely. */
  def parametersConst(fp: FP): Unit = parameters(_ => Right(()))(_ => fp)

  private[syntheke] def close(loc: SourceLocation): Unit =
    val nodeSpecs          = nodes.toVector.map { b =>
      val (direction, fnOpt) = b match
        case ib: InwardNodeBuilder[?]  => (NodeDirection.Inward, ib.fn)
        case ob: OutwardNodeBuilder[?] => (NodeDirection.Outward, ob.fn)
      val userFn             = fnOpt.getOrElse(
        throw new IllegalStateException(
          s"node ${b.id.show}: ${
              if direction == NodeDirection.Inward then "uFn" else "dFn"
            } is mandatory but was never set"
        )
      )
      val (order, declLoc)   = b match
        case ib: InwardNodeBuilder[?]  => (ib.order, ib.loc)
        case ob: OutwardNodeBuilder[?] => (ob.order, ob.loc)
      NodeSpec(
        name = b.id.name,
        direction = direction,
        protocol = b.protocol,
        fn = values => userFn(new ReadCtx(values)),
        refs = b.refDecls.toVector,
        order = order,
        loc = declLoc
      )
    }
    val (compute, combine) = params.getOrElse(
      throw new IllegalStateException(s"generator module ${id.show}: parameters(...) is mandatory but was never set")
    )
    st.modules(id) = GeneratorModuleSpec(
      id = id,
      entry = entry,
      nodes = nodeSpecs,
      dependencies = deps.toVector,
      dvSources = dvSrcs.toVector,
      dvSinks = dvSnks.toVector,
      computeProtocolParam = compute,
      combine = combine,
      loc = loc
    )
