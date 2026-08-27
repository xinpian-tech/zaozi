// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Resolved records produced by the Negotiate phase (doc @sec-resolved-records, @sec-generator-records). */

/** One settled design edge. */
final case class ResolvedEdge(
  bind:     BindId,
  protocol: Protocol,
  down:     Any,
  up:       Any,
  edge:     Any,
  interface: ProtocolBundle):
  def edgeAs(p: Protocol): p.Edge = edge.asInstanceOf[p.Edge]
  def downAs(p: Protocol): p.Down = down.asInstanceOf[p.Down]
  def upAs(p:   Protocol): p.Up   = up.asInstanceOf[p.Up]

/** A settled cross-protocol reference: the target node's edge parameter, providing domain information only. */
final case class ResolvedProtocolReference(
  refName:  String,
  referrer: ModuleNodeId,
  target:   ModuleNodeId,
  protocol: Protocol,
  edge: Any):
  def edgeAs(p: Protocol): p.Edge = edge.asInstanceOf[p.Edge]

/** One settled probe-sink group: the sink, its binds in declaration order, and the aggregate. */
final case class ResolvedDVGroup(
  sink:     DVSinkId,
  protocol: DVProtocol,
  binds:    Vector[DVBindId],
  downs:    Vector[Any],
  layers:   Vector[LayerPath],
  edge:     Any,
  interfaces: DVInterfaces):
  def edgeAs(p: DVProtocol): p.Edge = edge.asInstanceOf[p.Edge]

/** Per-source verification view entry (doc @sec-dv-protocol). */
final case class SourceView(
  source:    DVSourceId,
  bind:      DVBindId,
  protocol:  DVProtocol,
  edge:      Any,
  interface: ProtocolBundle,
  layer:     LayerPath)

/** Per-sink verification view entry. */
final case class SinkView(
  sink:       DVSinkId,
  binds:      Vector[DVBindId],
  protocol:   DVProtocol,
  edge:       Any,
  interfaces: DVInterfaces)

final case class VerificationView(sources: Vector[SourceView], sinks: Vector[SinkView])

/** One node's entry in a module's [[EdgeView]]: direction, its unique settled edge, and resolved references. */
final case class NodeView(
  node:      ModuleNodeId,
  direction: NodeDirection,
  edge:      ResolvedEdge,
  refs: Vector[ResolvedProtocolReference]):
  def ref(name: String): Option[ResolvedProtocolReference] = refs.find(_.refName == name)

/** The per-module projection of settled data read by `computeProtocolParam` (doc @dec-pp-local). */
final case class EdgeView(
  module: ModuleId,
  nodes:  Vector[NodeView], // node declaration order
  verification: VerificationView):
  def node(name: String):  Option[NodeView] = nodes.find(_.node.name == name)
  def apply(name: String): NodeView         =
    node(name).getOrElse(throw new NoSuchElementException(s"no node '$name' in EdgeView of ${module.show}"))

/** A settled generator module: registry entry, its view, and both parameter layers (doc @sec-two-layer-params). */
final case class ResolvedGeneratorModule(
  module:           ModuleId,
  entry:            GeneratorEntry[?],
  view:             EdgeView,
  protocolParam:    Any,
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

enum PortDirection derives CanEqual:
  case Input, Output

/** Stable origin of a planned port or wire. */
enum PlanOrigin derives CanEqual:
  case Design(bind: BindId)
  case Verification(bind: DVBindId)

/** A framework-generated dangle port on a wrapper module. Design edges plan one bundle port per crossing; probe routing
  * plans one pure-probe port per signal leaf, so probes never form aggregates in hardware.
  */
final case class PortPlan(
  module:    ModuleId,
  direction: PortDirection,
  name:      PortName,
  interface: ProtocolInterface,
  origin:    PlanOrigin,
  loc:       SourceLocation)

/** A local endpoint of a planned wire, inside one wrapper module. */
enum LocalEndpoint derives CanEqual:
  /** A dangle port of the wrapper itself. */
  case ThisPort(name: PortName)

  /** A port (or a sub-bundle of a port, for verification sinks) on a direct child instance. */
  case ChildPort(instance: String, port: PortName, sub: InterfacePath = InterfacePath.root)

/** A planned bundle-level wire inside one wrapper module, from source side to target side. */
final case class WirePlan(
  module: ModuleId,
  from:   LocalEndpoint,
  to:     LocalEndpoint,
  origin: PlanOrigin,
  loc:    SourceLocation)

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
  dvGroups:         Vector[ResolvedDVGroup],
  generatorModules: Vector[ResolvedGeneratorModule], // hierarchy preorder
  portPlans:        Vector[PortPlan],
  wirePlans:        Vector[WirePlan],
  layerDecls: Map[ModuleId, LayerTree]):
  def edge(bind:          BindId):   Option[ResolvedEdge]            = edges.find(_.bind == bind)
  def generatorModule(id: ModuleId): Option[ResolvedGeneratorModule] = generatorModules.find(_.module == id)
