// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** The design-authoring surface (doc @sec-build): everything a design author writes lives in this file — the [[Design]]
  * entry point, the generator registry entry, the declaration functions, the bind operator, the handles they return and
  * the return-channel types [[Dangles]] / [[Nodes]]. The scopes the declarations delegate to are the mechanism, in
  * `Builder.scala`.
  *
  * Naming (`sourcecode.Name`): declarations are named by the val they are bound to, like zaozi's instance naming:
  *
  * {{{
  * val spec = Design {
  *   val core = generator(coreEntry) {   // instance name "core"
  *     val out = outward(Wid).dFn(...)   // node name "out"
  *     ...
  *     out
  *   }
  * }
  * }}}
  *
  * When the val cannot carry the intended name — computed names in a loop, destructured returns — provide the given
  * explicitly: `given sourcecode.Name = sourcecode.Name(s"in$i")`. A def forwarding its own binding-site name must
  * contain exactly the one wrapper / generator call, or the Name would capture its internal declarations too.
  *
  * Locations (`sourcecode.File` / `sourcecode.Line`): the declaration site, stored in the spec for diagnostics only,
  * never as identity (doc @sec-identity).
  */

/** Entry point: run `body` as the root wrapper module and freeze everything it declared into an immutable
  * [[DesignSpec]], the input of negotiation. Declaration-site contracts fail on the spot while the body runs; the
  * design-wide contracts (bind ancestry, exactly one bind per inward node, …) are negotiation's checkpoint.
  */
object Design:
  def apply(
    body: WrapperScope ?=> Unit
  )(
    using
    file: sourcecode.File,
    line: sourcecode.Line
  ): DesignSpec =
    val st    = new BuildState
    val scope = new WrapperScope(ModuleId.root, st)
    st.moduleOrder += ModuleId.root
    body(
      using scope
    )
    scope.close((file, line))
    DesignSpec(
      modules = st.modules.toMap,
      moduleOrder = st.moduleOrder.toVector,
      binds = st.binds.toVector,
      testbench = st.testbenches.headOption
    )

/** Registry entry of one hardware generator: its identity across all phases plus the FullParam serialization (doc
  * @sec-generator-records).
  *   The name derives from the binding val, like every other declaration; it is the module-name stem and the tooling id
  *   of everything the generator enacts, so two distinct entries sharing a name are rejected by the structural check.
  *   The generator implementation lives beyond the serialization boundary and is bound to its entry in the elaboration
  *   module.
  */
final class GeneratorEntry[FP](
  using
  val fullParamRW: upickle.default.ReadWriter[FP],
  declaredName: sourcecode.Name):
  val name: String = declaredName.value

/** Instantiate a child structural module named by the binding val: its body composes child instances and `<--` binds
  * (doc @sec-module-kinds). Returns the body's dangling nodes ([[Dangles]]).
  */
def wrapper[A: Dangles](
  body: WrapperScope ?=> A
)(
  using
  ws:   WrapperScope,
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): A =
  ws.wrapper(name.value)(body)

/** Instantiate a child generator module named by the binding val, bound to registry entry `entry`: a leaf of the
  * hierarchy, implemented by a hardware generator (doc @sec-module-kinds). Its body declares nodes, dependencies, probe
  * sources and exactly one [[parameters]] computation; child instances and binds are rejected inside it. Returns the
  * body's dangling nodes ([[Dangles]]).
  */
def generator[FP, A: Dangles](
  entry: GeneratorEntry[FP]
)(body:  GeneratorScope[FP] ?=> A
)(
  using
  ws:    WrapperScope,
  name:  sourcecode.Name,
  file:  sourcecode.File,
  line:  sourcecode.Line
): A =
  ws.generator(name.value, entry)(body)

/** Declare an inward node (bind target) of protocol `p` on the enclosing generator module, named by the binding val.
  * Attach its mandatory up-parameter function with [[InwardNodeBuilder.uFn]] before the body returns.
  */
def inward(
  p:    Protocol
)(
  using
  gs:   GeneratorScope[?],
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): p.Inward =
  gs.inward(p)(name.value)

/** Declare an outward node (bind source) of protocol `p` on the enclosing generator module, named by the binding val.
  * Attach its mandatory down-parameter function with [[OutwardNodeBuilder.dFn]] before the body returns.
  */
def outward(
  p:    Protocol
)(
  using
  gs:   GeneratorScope[?],
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): p.Outward =
  gs.outward(p)(name.value)

/** Instantiate the testbench named by the binding val (doc @sec-dv-testbench): a special generator module, top level
  * only, at most one per design. Its body is an ordinary generator body — its nodes bind to the design's nodes with
  * `<--` and negotiate like any edge, terminating the design's outward-facing interfaces. Its one specialty: the
  * framework wires every probe leaf of the design into a matching input port (named by the leaf's port name, typed as
  * the resolved data). Its [[parameters]] computation reads the probe manifest from `EdgeView.probes` — complete
  * regardless of declaration order — to shape those ports and the full parameter.
  */
def testbench[FP, A: Dangles](
  entry: GeneratorEntry[FP]
)(body:  GeneratorScope[FP] ?=> A
)(
  using
  ws:    WrapperScope,
  name:  sourcecode.Name,
  file:  sourcecode.File,
  line:  sourcecode.Line
): A =
  ws.testbench(name.value, entry)(body)

/** Declare a module-internal parameter dependency from inward node `from` to outward node `to` and receive the two read
  * handles it grants: `to`'s dFn may read `from`'s settled `Down`, and `from`'s uFn may read `to`'s settled `Up` (doc @sec-generator-module).
  * A read not granted by a declared dependency fails negotiation at the reading function.
  */
def depend(
  from:     InwardNodeBuilder[?],
  to:       OutwardNodeBuilder[?]
)(
  using gs: GeneratorScope[?],
  file:     sourcecode.File,
  line:     sourcecode.Line
): (DownReader[from.protocol.Down], UpReader[to.protocol.Up]) =
  gs.depend(from, to)

/** Declare the enclosing generator's full-parameter computation, exactly once per body (doc @sec-two-layer-params): the
  * settled [[EdgeView]] plus the user parameters captured in the closure produce the FullParam handed to the hardware
  * generator, or a [[Violation]] when the settled edges exceed its capability.
  */
def parameters[FP](
  compute:  EdgeView => Either[Violation, FP]
)(
  using gs: GeneratorScope[FP]
): Unit =
  gs.parameters(compute)

/** Declare a probe source named by the binding val: the enclosing module publishes the verification data described by
  * `down`, as read-only probes confined to FIRRTL layer `layer` (doc @sec-dv-declarations). The framework forwards
  * every probe leaf automatically to the root — into the [[testbench]]'s matching data input when one is declared, as a
  * top-level probe port otherwise.
  *
  * Always bind the declaration to a val. This is the one named declaration returning `Unit`, so nothing forces the
  * binding: as a bare statement it would silently take its name from the enclosing definition instead.
  */
def dvSource(
  p:     DVProtocol
)(down:  p.Down,
  layer: LayerPath
)(
  using
  gs:    GeneratorScope[?],
  name:  sourcecode.Name,
  file:  sourcecode.File,
  line:  sourcecode.Line
): Unit =
  gs.dvSource(p)(name.value, down, layer)

/** Design bind `target <-- source`: the source's settled edge flows into the target, recorded in the enclosing wrapper
  * (`<-` itself is reserved by Scala). The shared type parameter guarantees one protocol object on both ends; ancestry
  * and the exactly-one-bind-per-inward-node rule are negotiation's checkpoint.
  */
extension [P <: Protocol](target: InwardNodeBuilder[P])
  infix def <--(
    source:   OutwardNodeBuilder[P]
  )(
    using ws: WrapperScope,
    file:     sourcecode.File,
    line:     sourcecode.Line
  ): Unit =
    ws.recordBind(source.id, target.id, (file, line))

/** Read handles granted by [[depend]]: the only way a port parameter function reads a peer node's settled value,
  * applied through the [[ReadCtx]] the function receives. The two subclasses keep the directions apart at the type
  * level — a dFn holds [[DownReader]]s, a uFn holds [[UpReader]]s.
  */
sealed abstract class Reader[T] private[syntheke] (private[syntheke] val node: ModuleNodeId)
final class DownReader[T] private[syntheke] (node: ModuleNodeId) extends Reader[T](node)
final class UpReader[T] private[syntheke] (node: ModuleNodeId)   extends Reader[T](node)

/** Values visible to one port parameter function evaluation: apply a read handle to get that peer's settled value. */
final class ReadCtx private[syntheke] (values: Map[ModuleNodeId, Any]):
  def apply[T](r: Reader[T]): T =
    values.getOrElse(r.node, throw new UndeclaredReadException(r.node)).asInstanceOf[T]

/** Handle of a declared cross-protocol reference: reads the target node's settled edge from the [[EdgeView]], typed by
  * the target's protocol (doc @sec-settle-pp).
  */
final class RefHandle[P <: Protocol] private[syntheke] (
  val protocol:                   P,
  private[syntheke] val referrer: ModuleNodeId,
  private[syntheke] val refName:  String)

/** A declared node: the surface shared by the two builders. */
sealed trait NodeBuilder[P <: Protocol]:
  val protocol: P
  def id:                      ModuleNodeId
  private[syntheke] def scope: GeneratorScope[?]

  /** Declare a cross-protocol reference to another node of the same module (its clock / power domain), named by the
    * binding val. The target's protocol is the expected protocol by construction; foreign-module targets are rejected
    * here. The returned [[RefHandle]] is the only way to read the reference back.
    */
  def ref(
    target: NodeBuilder[?]
  )(
    using
    name:   sourcecode.Name,
    file:   sourcecode.File,
    line:   sourcecode.Line
  ): target.protocol.Ref =
    require(
      target.id.module == id.module,
      s"cross-protocol reference '${name.value}' of ${id.show}: target ${target.id.show} is not a node of this module"
    )
    scope.recordRef(id.name, CrossProtocolRefSpec(name.value, target.id, (file, line)))
    new RefHandle[target.protocol.type](target.protocol, id, name.value)

/** An inward node under declaration; [[uFn]] must be attached exactly once before the body returns. */
final class InwardNodeBuilder[P <: Protocol] private[syntheke] (
  val protocol:                P,
  private[syntheke] val scope: GeneratorScope[?],
  val id:                      ModuleNodeId)
    extends NodeBuilder[P]:

  /** Attach the node's up-parameter function: read the settled `Up` of the outward nodes granted by [[depend]] and
    * produce this node's `Up`, or a [[Violation]] to fail negotiation (doc @sec-node-conn-proto).
    */
  def uFn(f: ReadCtx => Either[Violation, protocol.Up]): InwardNodeBuilder[P] =
    scope.recordFn(id.name, values => f(new ReadCtx(values)))
    this

/** An outward node under declaration; [[dFn]] must be attached exactly once before the body returns. */
final class OutwardNodeBuilder[P <: Protocol] private[syntheke] (
  val protocol:                P,
  private[syntheke] val scope: GeneratorScope[?],
  val id:                      ModuleNodeId)
    extends NodeBuilder[P]:

  /** Attach the node's down-parameter function: read the settled `Down` of the inward nodes granted by [[depend]] and
    * produce this node's `Down`, or a [[Violation]] to fail negotiation (doc @sec-node-conn-proto).
    */
  def dFn(f: ReadCtx => Either[Violation, protocol.Down]): OutwardNodeBuilder[P] =
    scope.recordFn(id.name, values => f(new ReadCtx(values)))
    this

/** Base for classes whose fields are a module's nodes: each `val x = inward(...)` field declares, names and exposes a
  * node in one line, and the instance itself is the body's returned container. Plain classes have no Mirror, so the
  * fields are not machine-checked — extending this trait is the author's declaration that they are nodes. The one
  * harmful thing a field could hold, the scope itself, is inert anyway: declarations outside the open scope fail on the
  * spot.
  */
trait Nodes

/** What a module body may return: its dangling nodes — node builders — plus `Unit`, `Option` / `Vector` / `Seq` of
  * them, products (tuples, case classes) whose fields all qualify, and [[Nodes]] classes. This is the only channel out
  * of a module body, so nothing else (readers, scopes, arbitrary values) can escape it.
  */
sealed trait Dangles[A]

object Dangles:
  private val evidence = new Dangles[Any] {}
  private def of[A]: Dangles[A] = evidence.asInstanceOf[Dangles[A]]

  given unit:                   Dangles[Unit]                  = of
  given inward[P <: Protocol]:  Dangles[InwardNodeBuilder[P]]  = of
  given outward[P <: Protocol]: Dangles[OutwardNodeBuilder[P]] = of
  given nodes[E <: Nodes]:      Dangles[E]                     = of
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
