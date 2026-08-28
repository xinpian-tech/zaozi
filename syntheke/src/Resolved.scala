// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Resolved records produced by the Negotiate phase (doc @sec-resolved-records, @sec-generator-records). */

/** One settled design edge. The typed readers demand the edge's own protocol object — with erased parameter types, a
  * foreign protocol would otherwise read silently mis-typed values.
  */
final case class ResolvedEdge(
  bind:     BindId,
  protocol: Protocol,
  down:     Any,
  up:       Any,
  edge:     Any,
  interface: ProtocolBundle):
  private def sameProtocol(p: Protocol): Unit   =
    require(p eq protocol, s"${bind.show}: read with a protocol object other than the edge's own")
  def edgeAs(p: Protocol):               p.Edge =
    sameProtocol(p)
    edge.asInstanceOf[p.Edge]
  def downAs(p: Protocol):               p.Down =
    sameProtocol(p)
    down.asInstanceOf[p.Down]
  def upAs(p: Protocol):                 p.Up   =
    sameProtocol(p)
    up.asInstanceOf[p.Up]

/** A settled cross-protocol reference: the target node's edge parameter, providing domain information only. */
final case class ResolvedProtocolReference(
  refName:  String,
  referrer: ModuleNodeId,
  target:   ModuleNodeId,
  protocol: Protocol,
  edge: Any):
  def edgeAs(p: Protocol): p.Edge =
    require(
      p eq protocol,
      s"reference '$refName' of ${referrer.show}: read with a protocol object other than the target's"
    )
    edge.asInstanceOf[p.Edge]

/** One node's entry in a module's [[EdgeView]]: direction, its unique settled edge, and resolved references. */
final case class NodeView(
  node:      ModuleNodeId,
  direction: NodeDirection,
  edge:      ResolvedEdge,
  refs:      Vector[ResolvedProtocolReference])

/** The per-module projection of settled data read by the module's `parameters` computation (doc @dec-pp-local).
  *
  * Reads are keyed by the node builders and reference handles declared in the module body, and the results are typed by
  * the handle's protocol — there is no lookup by name string. `probes` are the probe leaves the framework wires into
  * this module: the full manifest for the testbench — computed after the spec froze, so it is complete regardless of
  * declaration order — and empty for every other module (doc @sec-dv-testbench).
  */
final case class EdgeView(
  module: ModuleId,
  nodes:  Vector[NodeView], // node declaration order
  probes: Vector[ProbeSource]):

  def apply(n: NodeBuilder[?]): NodeView =
    val view = nodes.find(_.node == n.id)
    require(view.isDefined, s"node ${n.id.show} is not a node of EdgeView of ${module.show}")
    view.get

  def edgeOf(n: NodeBuilder[?]): n.protocol.Edge = apply(n).edge.edgeAs(n.protocol)

  def edgeOf(h: RefHandle[?]): h.protocol.Edge =
    val ref = nodes.find(_.node == h.referrer).flatMap(_.refs.find(_.refName == h.refName))
    require(
      ref.isDefined,
      s"reference '${h.refName}' of ${h.referrer.show} is not a reference of EdgeView of ${module.show}"
    )
    ref.get.edgeAs(h.protocol)

/** A settled generator module: registry entry, its view, and the computed full parameter (doc @sec-two-layer-params).
  */
final case class ResolvedGeneratorModule(
  module:           ModuleId,
  entry:            GeneratorEntry[?],
  view:             EdgeView,
  fullParam:        Any,
  encodedFullParam: ujson.Value)

// ============ Cross-hierarchy planning (doc @ch-hierarchy) ============

/** Reversible name-segment encoding for framework-generated dangle ports (doc @sec-port-naming).
  *
  * Segments join with `_`; literal `_` escapes to `$u`, `-` to `$m` and `$` to `$$` — all legal in FIRRTL identifiers —
  * so distinct segment sequences yield distinct, decodable strings.
  */
final case class PortName(segments: Vector[String]):
  def ++(that: PortName): PortName = PortName(segments ++ that.segments)
  def encoded:            String   = segments.map(PortName.escape).mkString("_")

object PortName:
  def apply(segments: String*):        PortName = PortName(segments.toVector)
  private def escape(segment: String): String   =
    segment.replace("$", "$$").replace("_", "$u").replace("-", "$m")

  /** Dangle-port name on wrapper `m` for the connection ending at `endpoint`'s port with `base` segments. */
  private[syntheke] def dangle(m: ModuleId, endpoint: ModuleId, base: PortName): PortName =
    PortName(endpoint.path.drop(m.path.length).flatMap(inst => Vector("inst", inst))) ++ base

  /** Dangle base segments of one probe leaf: `dv-source`, the source name, the leaf path, `out`. */
  private[syntheke] def dvBase(source: String, leafPath: InterfacePath): PortName =
    PortName("dv-source" +: source +: leafPath.nameSegments :+ "out")

/** One probe leaf as a testbench receives it: the harness input-port name (the leaf's root-scope dangle name — also the
  * top-level port name when nothing consumes it), the data type after `ref.resolve`, and the leaf's path in its source
  * interface.
  */
final case class ProbeLeaf(
  portName: String,
  tpe:      ProtocolInterface,
  path:     InterfacePath)
    derives upickle.default.ReadWriter

/** One probe source in the design's probe manifest (doc @sec-dv-testbench): identity, protocol-encoded `Down`, layer,
  * and leaves. The manifest is a pure function of the frozen [[DesignSpec]] — every field was recorded at the
  * `dvSource` declaration — and it is serializable for tooling.
  */
final case class ProbeSource(
  id:     DVSourceId,
  down:   ujson.Value,
  layer:  LayerPath,
  leaves: Vector[ProbeLeaf])
    derives upickle.default.ReadWriter

object ProbeSource:
  /** The design's probe manifest, in hierarchy preorder then declaration order. */
  def manifest(spec: DesignSpec): Vector[ProbeSource] =
    of(spec.generatorModules.flatMap(g => g.dvSources.map(g.id -> _)))

  private[syntheke] def of(sources: Vector[(ModuleId, DVSourceSpec)]): Vector[ProbeSource] =
    sources.map { (module, s) =>
      val leaves = ProtocolBundle.leaves(s.interface).collect { case (path, ProtocolInterface.Probe(inner, _)) =>
        ProbeLeaf(PortName.dangle(ModuleId.root, module, PortName.dvBase(s.name, path)).encoded, inner, path)
      }
      val down   = upickle.default.writeJs(s.down)(
        using s.protocol.downRW.asInstanceOf[upickle.default.ReadWriter[Any]]
      )
      ProbeSource(DVSourceId(module, s.name), down, s.layer, leaves)
    }

enum PortDirection derives CanEqual:
  case Input, Output

/** Stable origin of a planned port or wire. */
enum PlanOrigin derives CanEqual:
  case Design(bind: BindId)
  case Verification(source: DVSourceId)

/** A framework-generated dangle port on a wrapper module. Design edges plan one bundle port per crossing; probe routing
  * plans one pure-probe port per interface leaf — a Probe node is one leaf, its inner may be an aggregate, and the port
  * is a single reference either way.
  */
final case class PortPlan(
  module:    ModuleId,
  direction: PortDirection,
  name:      PortName,
  interface: ProtocolInterface,
  origin:    PlanOrigin,
  loc:       (sourcecode.File, sourcecode.Line))

/** A local endpoint of a planned wire, inside one wrapper module. */
enum LocalEndpoint derives CanEqual:
  /** A dangle port of the wrapper itself. */
  case ThisPort(name: PortName)

  /** A port on a direct child instance. */
  case ChildPort(instance: String, port: PortName)

/** A planned bundle-level wire inside one wrapper module, from source side to target side. */
final case class WirePlan(
  module: ModuleId,
  from:   LocalEndpoint,
  to:     LocalEndpoint,
  origin: PlanOrigin,
  loc:    (sourcecode.File, sourcecode.Line))

/** FIRRTL layer declarations as a prefix tree (doc @sec-layers). */
final case class LayerTree(children: Map[String, LayerTree]):
  def merge(that: LayerTree): LayerTree =
    LayerTree(
      (children.keySet ++ that.children.keySet).map { k =>
        k -> children.getOrElse(k, LayerTree.empty).merge(that.children.getOrElse(k, LayerTree.empty))
      }.toMap
    )
  def add(path: LayerPath):   LayerTree = merge(LayerTree.of(path))
  def isEmpty:                Boolean   = children.isEmpty

  /** Paths in name order, parents before children — the emission order. */
  def paths(prefix: Vector[String] = Vector.empty): Vector[Vector[String]] =
    children.toVector.sortBy(_._1).flatMap { (name, sub) =>
      (prefix :+ name) +: sub.paths(prefix :+ name)
    }

object LayerTree:
  val empty:               LayerTree = LayerTree(Map.empty)
  def of(path: LayerPath): LayerTree =
    path.segments.foldRight(empty)((seg, sub) => LayerTree(Map(seg -> sub)))

/** The Negotiate phase output: the spec plus every settled record and plan (doc @sec-triptych). */
final case class ResolvedDesign(
  spec:             DesignSpec,
  edges:            Vector[ResolvedEdge],            // bind declaration order
  generatorModules: Vector[ResolvedGeneratorModule], // hierarchy preorder
  portPlans:        Vector[PortPlan],
  wirePlans:        Vector[WirePlan],
  layerDecls: Map[ModuleId, LayerTree]):
  /** The unique settled edge at `node` — every boundary node is bound exactly once. */
  def edgeAt(node: ModuleNodeId): ResolvedEdge =
    val found = edges.find(e => e.bind.source == node || e.bind.target == node)
    require(found.isDefined, s"${node.show} has no settled edge")
    found.get

  def generatorModule(id: ModuleId): Option[ResolvedGeneratorModule] = generatorModules.find(_.module == id)

  /** The design's probe sources with their leaves (doc @sec-dv-testbench). */
  def probes: Vector[ProbeSource] = ProbeSource.manifest(spec)
