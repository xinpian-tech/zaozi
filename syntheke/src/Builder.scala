// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

import scala.collection.mutable

/** The mechanism behind the authoring surface in `Dsl.scala` (doc @sec-build).
  *
  * [[Design]] opens the root wrapper scope over a fresh [[BuildState]]; wrapper scopes instantiate child modules and
  * record binds; generator scopes record nodes, module-internal parameter dependencies, probe sources and the parameter
  * computation. When a body returns its scope freezes into the module's spec, and when the design body returns the
  * state freezes into the immutable [[DesignSpec]].
  *
  * Declaration-site contracts are enforced on the spot with `require` — duplicate names, foreign-module targets and
  * double registration fail at the offending line. What the type system already guarantees (bind ends share one
  * protocol object, dependency endpoints have the right directions) is checked nowhere else.
  */

/** Recording state of one `Design` build; append-only, frozen into the [[DesignSpec]] when the body returns. */
private final class BuildState:
  val modules     = mutable.Map.empty[ModuleId, ModuleSpec]
  val moduleOrder = mutable.ArrayBuffer.empty[ModuleId]
  val binds       = mutable.ArrayBuffer.empty[BindDecl]
  val testbenches = mutable.ArrayBuffer.empty[ModuleId] // at most one, enforced at the declaration

  // Generator scopes currently under construction. Context functions stack rather than shadow, so the enclosing
  // WrapperScope stays visible inside a generator body; structure and binds declared there would silently attach to
  // the outer wrapper. Generator modules are leaves (doc @sec-module-kinds) — reject at the declaration.
  val openLeaves = mutable.ArrayBuffer.empty[ModuleId]
  def requireNotInLeaf(what: String): Unit =
    require(
      openLeaves.isEmpty,
      s"$what declared inside generator body ${openLeaves.last.show}: generator modules are leaves"
    )

private[syntheke] final class UndeclaredReadException(val node: ModuleNodeId)
    extends RuntimeException(s"read of ${node.show} which is not a declared dependency of this function")

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

  /** Instantiate a child wrapper module; returns the body's dangling nodes. */
  private[syntheke] def wrapper[A: Dangles](
    name: String
  )(body: WrapperScope ?=> A
  )(
    using
    file: sourcecode.File,
    line: sourcecode.Line
  ): A =
    val childId = addChild(name)
    val scope   = new WrapperScope(childId, st)
    val result  = body(
      using scope
    )
    scope.close((file, line))
    result

  /** Instantiate a child generator module bound to a registry entry; returns the body's dangling nodes. */
  private[syntheke] def generator[FP, A: Dangles](
    name:  String,
    entry: GeneratorEntry[FP]
  )(body:  GeneratorScope[FP] ?=> A
  )(
    using
    file:  sourcecode.File,
    line:  sourcecode.Line
  ): A =
    val childId = addChild(name)
    st.openLeaves += childId
    val scope   = new GeneratorScope[FP](childId, st, entry)
    val result  = body(
      using scope
    )
    st.openLeaves.dropRightInPlace(1)
    scope.close((file, line))
    result

  /** Instantiate the testbench generator module (doc @sec-dv-testbench): root scope only, at most one per design. */
  private[syntheke] def testbench[FP, A: Dangles](
    name:  String,
    entry: GeneratorEntry[FP]
  )(body:  GeneratorScope[FP] ?=> A
  )(
    using
    file:  sourcecode.File,
    line:  sourcecode.Line
  ): A =
    require(id == ModuleId.root, s"testbench '$name' declared in ${id.show}: the testbench lives on the top level")
    require(
      st.testbenches.isEmpty,
      s"testbench '$name': testbench '${st.testbenches.headOption.fold("")(_.show)}' already declared"
    )
    val result = generator(name, entry)(body)
    st.testbenches += (id / name)
    result

  private[syntheke] def recordBind(
    source: ModuleNodeId,
    target: ModuleNodeId,
    loc:    (sourcecode.File, sourcecode.Line)
  ): Unit =
    st.requireNotInLeaf(s"bind ${source.show} -> ${target.show}")
    st.binds += BindDecl(st.binds.size, source, target, id, loc)

  private[syntheke] def close(loc: (sourcecode.File, sourcecode.Line)): Unit =
    st.modules(id) = WrapperModuleSpec(id, children.toVector, loc)

/** A generator module under construction: nodes, dependencies, probe sources and parameter functions. */
final class GeneratorScope[FP] private[syntheke] (val id: ModuleId, st: BuildState, entry: GeneratorEntry[FP]):
  private val nodes  = mutable.ArrayBuffer.empty[(NodeBuilder[?], NodeDirection, (sourcecode.File, sourcecode.Line))]
  private val fns    = mutable.Map.empty[String, Map[ModuleNodeId, Any] => Either[Violation, Any]]
  private val refs   = mutable.ArrayBuffer.empty[(String, CrossProtocolRefSpec)]
  private val deps   = mutable.ArrayBuffer.empty[ParamDependencySpec]
  private val dvSrcs = mutable.ArrayBuffer.empty[DVSourceSpec]
  private val params = mutable.ArrayBuffer.empty[EdgeView => Either[Violation, Any]]

  /** Declarations are only legal while this scope is the generator currently under construction — a closure capturing
    * the scope (a dFn running at negotiation, say) cannot declare into a frozen module.
    */
  private def requireOpen(): Unit =
    require(st.openLeaves.lastOption.contains(id), s"declaration on ${id.show} outside its builder scope")

  private def reserveName(name: String): Unit =
    requireOpen()
    DeclaredName.require(name, s"declaration name in ${id.show}")
    val taken = nodes.exists(_._1.id.name == name) || dvSrcs.exists(_.name == name)
    require(!taken, s"duplicate declaration name '$name' in ${id.show}")

  private[syntheke] def recordFn(name: String, f: Map[ModuleNodeId, Any] => Either[Violation, Any]): Unit =
    requireOpen()
    require(!fns.contains(name), s"port parameter function of ${ModuleNodeId(id, name).show} already set")
    fns(name) = f

  private[syntheke] def recordRef(nodeName: String, spec: CrossProtocolRefSpec): Unit =
    requireOpen()
    require(
      !refs.exists((n, s) => n == nodeName && s.refName == spec.refName),
      s"duplicate cross-protocol reference '${spec.refName}' on ${ModuleNodeId(id, nodeName).show}"
    )
    refs += (nodeName -> spec)

  /** Declare a named inward node of protocol `p`. */
  private[syntheke] def inward(
    p:    Protocol
  )(name: String
  )(
    using
    file: sourcecode.File,
    line: sourcecode.Line
  ): p.Inward =
    reserveName(name)
    val b = new InwardNodeBuilder[p.type](p, this, ModuleNodeId(id, name))
    nodes += ((b, NodeDirection.Inward, (file, line)))
    b

  /** Declare a named outward node of protocol `p`. */
  private[syntheke] def outward(
    p:    Protocol
  )(name: String
  )(
    using
    file: sourcecode.File,
    line: sourcecode.Line
  ): p.Outward =
    reserveName(name)
    val b = new OutwardNodeBuilder[p.type](p, this, ModuleNodeId(id, name))
    nodes += ((b, NodeDirection.Outward, (file, line)))
    b

  /** Declare one module-internal parameter dependency and receive the two read handles it grants: the outward node's
    * dFn may read the inward node's `Down`, the inward node's uFn may read the outward node's `Up` (doc @sec-generator-module).
    * Both endpoints must be nodes of this module; a pair declares at most once.
    */
  private[syntheke] def depend(
    from: InwardNodeBuilder[?],
    to:   OutwardNodeBuilder[?]
  )(
    using
    file: sourcecode.File,
    line: sourcecode.Line
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
    deps += ParamDependencySpec(from.id.name, to.id.name, deps.size, (file, line))
    (new DownReader[from.protocol.Down](from.id), new UpReader[to.protocol.Up](to.id))

  /** Declare a probe source providing its verification `Down` and FIRRTL layer path. The interface is derived and
    * checked here — every interface leaf must be a Probe carrying the declared layer (its inner may be an aggregate),
    * with no Flipped anywhere — so a protocol violating the contract fails at the declaration.
    */
  private[syntheke] def dvSource(
    p:     DVProtocol
  )(name:  String,
    down:  p.Down,
    layer: LayerPath
  )(
    using
    file:  sourcecode.File,
    line:  sourcecode.Line
  ): Unit =
    reserveName(name)
    val interface = p.interfaceOf(down, layer)
    require(
      !ProtocolInterface.containsFlipped(interface),
      s"probe source ${id.show}#$name: interface contains Flipped fields, but a probe is one-directional"
    )
    ProtocolBundle.leaves(interface).foreach { (path, leaf) =>
      require(
        leaf match
          case ProtocolInterface.Probe(_, l) => l == layer
          case _                             => false
        ,
        s"probe source ${id.show}#$name: leaf ${path.show} must be a Probe carrying layer ${layer.show}"
      )
    }
    dvSrcs += DVSourceSpec(name, p, down, layer, interface, dvSrcs.size, (file, line))

  /** Declare the full-parameter computation (doc @sec-two-layer-params): the negotiated `EdgeView` plus the user
    * parameters captured in the closure produce the `FullParam`; exactly once per module.
    */
  private[syntheke] def parameters(compute: EdgeView => Either[Violation, FP]): Unit =
    requireOpen()
    require(params.isEmpty, s"parameters of ${id.show} already set")
    params += compute.asInstanceOf[EdgeView => Either[Violation, Any]]

  private[syntheke] def close(loc: (sourcecode.File, sourcecode.Line)): Unit =
    val nodeSpecs = nodes.toVector.zipWithIndex.map { case ((b, direction, declLoc), order) =>
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
    val compute   = params.headOption.getOrElse(
      throw new IllegalStateException(s"generator module ${id.show}: parameters(...) is mandatory but was never set")
    )
    st.modules(id) = GeneratorModuleSpec(
      id = id,
      entry = entry,
      nodes = nodeSpecs,
      dependencies = deps.toVector,
      dvSources = dvSrcs.toVector,
      computeFullParam = compute,
      loc = loc
    )
