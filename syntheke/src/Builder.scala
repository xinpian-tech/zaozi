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
  * Declaration-site contracts are enforced on the spot with `require` — duplicate names, foreign-module targets and
  * double registration fail at the offending line. What the type system already guarantees (bind ends share one
  * protocol object, dependency endpoints have the right directions) is checked nowhere else.
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

/** Recording state of one `Design` build; append-only, frozen into the [[DesignSpec]] when the body returns. */
private final class BuildState:
  val modules     = mutable.Map.empty[ModuleId, ModuleSpec]
  val moduleOrder = mutable.ArrayBuffer.empty[ModuleId]
  val binds       = mutable.ArrayBuffer.empty[BindDecl]
  val dvBinds     = mutable.ArrayBuffer.empty[DVBindDecl]
  val protocols   = mutable.ArrayBuffer.empty[(ProtocolId, AnyRef)]
  val generators  = mutable.ArrayBuffer.empty[GeneratorEntry[?]]

  // Generator scopes currently under construction. Context functions stack rather than shadow, so the enclosing
  // WrapperScope stays visible inside a generator body; structure and binds declared there would silently attach to
  // the outer wrapper. Generator modules are leaves (doc @sec-module-kinds) — reject at the declaration.
  val openLeaves = mutable.ArrayBuffer.empty[ModuleId]
  def requireNotInLeaf(what: String): Unit =
    require(
      openLeaves.isEmpty,
      s"$what declared inside generator body ${openLeaves.last.show}: generator modules are leaves"
    )

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
  def id:                      ModuleNodeId
  private[syntheke] def scope: GeneratorScope[?]

  /** Declare a cross-protocol reference to another node of the same module (clock / power domain). The target's
    * protocol is the expected protocol by construction; foreign-module targets are rejected here.
    */
  def ref(
    name:      String,
    target:    NodeBuilder[?]
  )(
    using loc: SourceLocation
  ): this.type =
    require(
      target.id.module == id.module,
      s"cross-protocol reference '$name' of ${id.show}: target ${target.id.show} is not a node of this module"
    )
    scope.recordRef(id.name, CrossProtocolRefSpec(name, target.id, target.protocol.id, loc))
    this

/** An inward node under declaration; `uFn` must be attached exactly once before the scope closes. */
final class InwardNodeBuilder[P <: Protocol] private[syntheke] (
  val protocol:                P,
  private[syntheke] val scope: GeneratorScope[?],
  val id:                      ModuleNodeId)
    extends NodeBuilder[P]:
  def uFn(f: ReadCtx => Either[PropagationViolation, protocol.Up]): this.type =
    scope.recordFn(id.name, values => f(new ReadCtx(values)))
    this

/** An outward node under declaration; `dFn` must be attached exactly once before the scope closes. */
final class OutwardNodeBuilder[P <: Protocol] private[syntheke] (
  val protocol:                P,
  private[syntheke] val scope: GeneratorScope[?],
  val id:                      ModuleNodeId)
    extends NodeBuilder[P]:
  def dFn(f: ReadCtx => Either[PropagationViolation, protocol.Down]): this.type =
    scope.recordFn(id.name, values => f(new ReadCtx(values)))
    this

/** Verification endpoint handles. */
final class DVSourceRef[P <: DVProtocol] private[syntheke] (val protocol: P, val id: DVSourceId)
final class DVSinkRef[P <: DVProtocol] private[syntheke] (val protocol: P, val id: DVSinkId)

/** What a module body may return: its dangling endpoints — node builders and verification endpoint handles — plus
  * `Unit`, `Option` / `Vector` / `Seq` of them, and products (tuples, case classes) whose fields all qualify. This is
  * the only channel out of a module body, so nothing else (readers, scopes, arbitrary values) can escape it.
  */
sealed trait Dangles[A]

object Dangles:
  private val evidence = new Dangles[Any] {}
  private def of[A]: Dangles[A] = evidence.asInstanceOf[Dangles[A]]

  given unit:                      Dangles[Unit]                  = of
  given inward[P <: Protocol]:     Dangles[InwardNodeBuilder[P]]  = of
  given outward[P <: Protocol]:    Dangles[OutwardNodeBuilder[P]] = of
  given dvSource[P <: DVProtocol]: Dangles[DVSourceRef[P]]        = of
  given dvSink[P <: DVProtocol]:   Dangles[DVSinkRef[P]]          = of
  given option[A](
    using Dangles[A]
  ): Dangles[Option[A]] = of
  given vector[A](
    using Dangles[A]
  ): Dangles[Vector[A]] = of
  given seq[A](
    using Dangles[A]
  ): Dangles[Seq[A]] = of

  inline given product[A <: Product](
    using m: scala.deriving.Mirror.ProductOf[A]
  ): Dangles[A] =
    allDangles[m.MirroredElemTypes]
    of

  private inline def allDangles[T <: Tuple]: Unit =
    import scala.compiletime.{erasedValue, summonInline}
    inline erasedValue[T] match
      case _: EmptyTuple => ()
      case _: (h *: t)   =>
        summonInline[Dangles[h]]
        allDangles[t]

/** Design bind: `target <-- source`, recorded in the enclosing wrapper scope. The shared type parameter guarantees one
  * protocol object on both ends.
  */
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

  private def addChild(name: String): ModuleId =
    st.requireNotInLeaf(s"instance '$name'")
    DeclaredName.require(name, s"instance name in ${id.show}")
    require(!children.contains(name), s"duplicate child instance name '$name' in ${id.show}")
    children += name
    val childId = id / name
    st.moduleOrder += childId
    childId

  /** Instantiate a child wrapper module; returns the body's dangling endpoints. */
  def wrapper[A: Dangles](
    name:      String
  )(body:      WrapperScope ?=> A
  )(
    using loc: SourceLocation
  ): A =
    val childId = addChild(name)
    val scope   = new WrapperScope(childId, st)
    val result  = body(
      using scope
    )
    scope.close(loc)
    result

  /** Instantiate a child generator module bound to a registry entry; returns the body's dangling endpoints. */
  def generator[FP, A: Dangles](
    name:  String,
    entry: GeneratorEntry[FP]
  )(body:  GeneratorScope[FP] ?=> A
  )(
    using
    loc:   SourceLocation
  ): A =
    val childId = addChild(name)
    st.registerGenerator(entry)
    st.openLeaves += childId
    val scope   = new GeneratorScope[FP](childId, st, entry)
    val result  = body(
      using scope
    )
    st.openLeaves.dropRightInPlace(1)
    scope.close(loc)
    result

  private[syntheke] def recordBind(source: ModuleNodeId, target: ModuleNodeId, loc: SourceLocation): Unit =
    st.requireNotInLeaf(s"bind ${source.show} -> ${target.show}")
    st.binds += BindDecl(st.binds.size, source, target, id, loc)
  private[syntheke] def recordDVBind(source: DVSourceId, sink: DVSinkId, loc: SourceLocation):       Unit =
    st.requireNotInLeaf(s"verification bind ${source.show} -> ${sink.show}")
    st.dvBinds += DVBindDecl(st.dvBinds.size, source, sink, id, loc)

  private[syntheke] def close(loc: SourceLocation): Unit =
    st.modules(id) = WrapperModuleSpec(id, children.toVector, loc)

/** A generator module under construction: nodes, dependencies, verification endpoints and parameter functions. */
final class GeneratorScope[FP] private[syntheke] (val id: ModuleId, st: BuildState, entry: GeneratorEntry[FP]):
  private val nodes  = mutable.ArrayBuffer.empty[(NodeBuilder[?], NodeDirection, SourceLocation)]
  private val fns    = mutable.Map.empty[String, Map[ModuleNodeId, Any] => Either[PropagationViolation, Any]]
  private val refs   = mutable.ArrayBuffer.empty[(String, CrossProtocolRefSpec)]
  private val deps   = mutable.ArrayBuffer.empty[ParamDependencySpec]
  private val dvSrcs = mutable.ArrayBuffer.empty[DVSourceSpec]
  private val dvSnks = mutable.ArrayBuffer.empty[DVSinkSpec]
  private val params = mutable.ArrayBuffer.empty[(EdgeView => Either[CapabilityViolation, Any], Any => Any)]

  /** Declarations are only legal while this scope is the generator currently under construction — a closure capturing
    * the scope (a dFn running at negotiation, say) cannot declare into a frozen module.
    */
  private def requireOpen(): Unit =
    require(st.openLeaves.lastOption.contains(id), s"declaration on ${id.show} outside its builder scope")

  private def reserveName(name: String): Unit =
    requireOpen()
    DeclaredName.require(name, s"endpoint name in ${id.show}")
    val taken = nodes.exists(_._1.id.name == name) || dvSrcs.exists(_.name == name) || dvSnks.exists(_.name == name)
    require(!taken, s"duplicate endpoint name '$name' in ${id.show}")

  private[syntheke] def recordFn(name: String, f: Map[ModuleNodeId, Any] => Either[PropagationViolation, Any]): Unit =
    requireOpen()
    require(!fns.contains(name), s"port parameter function of ${ModuleNodeId(id, name).show} already set")
    fns(name) = f

  private[syntheke] def recordRef(name: String, spec: CrossProtocolRefSpec): Unit =
    requireOpen()
    refs += (name -> spec)

  /** Declare a named inward node of protocol `p`. */
  def inward(
    p:         Protocol
  )(name:      String
  )(
    using loc: SourceLocation
  ): InwardNodeBuilder[p.type] =
    reserveName(name)
    st.registerProtocol(p.id, p)
    val b = new InwardNodeBuilder[p.type](p, this, ModuleNodeId(id, name))
    nodes += ((b, NodeDirection.Inward, loc))
    b

  /** Declare a named outward node of protocol `p`. */
  def outward(
    p:         Protocol
  )(name:      String
  )(
    using loc: SourceLocation
  ): OutwardNodeBuilder[p.type] =
    reserveName(name)
    st.registerProtocol(p.id, p)
    val b = new OutwardNodeBuilder[p.type](p, this, ModuleNodeId(id, name))
    nodes += ((b, NodeDirection.Outward, loc))
    b

  /** Declare one module-internal parameter dependency and receive the two read handles it grants: the outward node's
    * dFn may read the inward node's `Down`, the inward node's uFn may read the outward node's `Up` (doc @sec-generator-module).
    * Both endpoints must be nodes of this module; a pair declares at most once.
    */
  def depend(
    from:      InwardNodeBuilder[?],
    to:        OutwardNodeBuilder[?]
  )(
    using loc: SourceLocation
  ): (DownReader[from.protocol.Down], UpReader[to.protocol.Up]) =
    requireOpen()
    require(
      from.id.module == id && to.id.module == id,
      s"parameter dependency endpoints ${from.id.show} -> ${to.id.show} must both be nodes of ${id.show}"
    )
    require(
      !deps.exists(d => d.from == from.id.name && d.to == to.id.name),
      s"duplicate parameter dependency ${from.id.show} -> ${to.id.show}"
    )
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
    reserveName(name)
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
    reserveName(name)
    st.registerProtocol(p.id, p)
    dvSnks += DVSinkSpec(name, p, dvSrcs.size + dvSnks.size, loc)
    new DVSinkRef[p.type](p, DVSinkId(id, name))

  /** Declare `computeProtocolParam` and `combine` (doc @sec-two-layer-params); exactly once per module. */
  def parameters[PP](compute: EdgeView => Either[CapabilityViolation, PP])(combine: PP => FP): Unit =
    requireOpen()
    require(params.isEmpty, s"parameters of ${id.show} already set")
    params += ((compute, pp => combine(pp.asInstanceOf[PP])))

  /** A generator whose full parameter ignores the negotiation result entirely. */
  def parametersConst(fp: FP): Unit = parameters(_ => Right(()))(_ => fp)

  private[syntheke] def close(loc: SourceLocation): Unit =
    val nodeSpecs          = nodes.toVector.zipWithIndex.map { case ((b, direction, declLoc), order) =>
      val fn = fns.getOrElse(
        b.id.name,
        throw new IllegalStateException(
          s"node ${b.id.show}: ${
              if direction == NodeDirection.Inward then "uFn" else "dFn"
            } is mandatory but was never set"
        )
      )
      NodeSpec(
        name = b.id.name,
        direction = direction,
        protocol = b.protocol,
        fn = fn,
        refs = refs.collect { case (n, spec) if n == b.id.name => spec }.toVector,
        order = order,
        loc = declLoc
      )
    }
    val (compute, combine) = params.headOption.getOrElse(
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
