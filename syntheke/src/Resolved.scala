package me.jiuyang.syntheke

import scala.collection.immutable.{SortedMap, VectorMap}

final case class ResolvedEdge private[syntheke] (
  bind:     BindId,
  protocol: Protocol,
  down:     Any,
  up:       Any,
  edge:     Any,
  interface: ProtocolInterface.Bundle):
  def edgeAs(p: Protocol): p.Edge =
    require(p eq protocol, s"${bind.show}: read with a protocol object other than the edge's own")
    edge.asInstanceOf[p.Edge]

object ResolvedEdge:
  /** The edge of the bind `node` is in. */
  private[syntheke] def at(edges: Vector[ResolvedEdge], node: ModuleNodeId): ResolvedEdge =
    edges
      .find(e => e.bind.source == node || e.bind.target == node)
      .getOrElse(
        throw IllegalArgumentException(s"${node.show} has no settled edge")
      )

final case class NodeView private[syntheke] (node: ModuleNodeId, edge: ResolvedEdge)

final case class EdgeView private[syntheke] (
  module: ModuleId,
  nodes:  Vector[NodeView],
  private[syntheke] val loc: SourceLoc):

  private[syntheke] def lookupEdge(n: Port[?]): n.protocol.Edge =
    nodes
      .find(_.node == n.id)
      .getOrElse(fail(s"${n.id.show} is not a node of ${module.show}", loc))
      .edge
      .edgeAs(n.protocol)

final case class ResolvedGeneratorModule private[syntheke] (
  module:           ModuleId,
  view:             EdgeView,
  fullParam:        Any,
  probeDeclaration: ProbeDeclaration)

final case class PortName private[syntheke] (segments: Vector[String], private val literalName: Option[String] = None):
  def ++(that: PortName): PortName = PortName(segments ++ that.segments)
  def encoded:            String   = literalName.getOrElse(segments.map(PortName.escape).mkString("_"))

object PortName:
  private[syntheke] def literal(name: String): PortName = PortName(Vector(name), Some(name))
  private def escape(segment: String):         String   =
    segment.replace("$", "$$").replace("_", "$u").replace("-", "$m")

  private[syntheke] def dangle(m: ModuleId, endpoint: ModuleId, base: PortName): PortName =
    PortName(endpoint.path.drop(m.path.length).flatMap(inst => Vector("inst", inst))) ++ base

  private[syntheke] def probeBase(portName: String): PortName = PortName(Vector("probe", portName, "out"))

enum PortDirection derives CanEqual:
  case Input, Output

/** How a planned wire is emitted: a data connection, a probe reference passed up a level, or a probe read into data. */
enum WireKind derives CanEqual:
  case Connect, DefineReference, ReadReference

final case class PortPlan private[syntheke] (
  module:    ModuleId,
  direction: PortDirection,
  name:      PortName,
  interface: ProtocolInterface,
  loc:       SourceLoc)

enum LocalEndpoint derives CanEqual:
  case ThisPort(name: PortName)

  case ChildPort(instance: String, port: PortName)

final case class WirePlan private[syntheke] (
  module: ModuleId,
  from:   LocalEndpoint,
  to:     LocalEndpoint,
  kind:   WireKind,
  loc:    SourceLoc)

/** Layers by name, each with the layers nested in it. */
final case class LayerTree private[syntheke] (children: SortedMap[String, LayerTree]):
  def merge(that: LayerTree): LayerTree =
    LayerTree(that.children.foldLeft(children) { case (merged, (name, sub)) =>
      merged.updated(name, merged.getOrElse(name, LayerTree.empty).merge(sub))
    })
  def add(path: LayerPath):   LayerTree = merge(LayerTree.of(path))
  def isEmpty:                Boolean   = children.isEmpty

  def paths(prefix: Vector[String] = Vector.empty): Vector[Vector[String]] =
    children.toVector.flatMap((name, sub) => (prefix :+ name) +: sub.paths(prefix :+ name))

  /** The paths that end in a layer without children. */
  def leaves: Vector[Vector[String]] =
    children.toVector.flatMap((name, sub) => if sub.isEmpty then Vector(Vector(name)) else sub.leaves.map(name +: _))

object LayerTree:
  val empty:                       LayerTree = LayerTree(SortedMap.empty)
  private def of(path: LayerPath): LayerTree =
    path.segments.foldRight(empty)((seg, sub) => LayerTree(SortedMap(seg -> sub)))

final case class ResolvedDesign private[syntheke] (
  spec:             DesignSpec,
  edges:            Vector[ResolvedEdge],
  generatorModules: Vector[ResolvedGeneratorModule],
  portPlans:        Vector[PortPlan],
  wirePlans:        Vector[WirePlan],
  layerDecls:       VectorMap[ModuleId, LayerTree],
  probes:           ProbeCatalog,
  observations:     VectorMap[ModuleId, ProbeBindings],
  private[syntheke] val dependencies: Vector[(ModuleId, ResolvedDesign)]):
  def edgeAt(node: ModuleNodeId): ResolvedEdge = ResolvedEdge.at(edges, node)

  private[syntheke] def boundaryEdge(boundary: Boundary[?], loc: SourceLoc): ResolvedEdge =
    check(spec.boundaries.exists(_.terminal eq boundary.terminal), loc)(
      s"boundary ${boundary.id.show} is not a boundary of ${spec.wrapper(spec.root).get.moduleName}"
    )
    edgeAt(boundary.terminal.id)

  def generatorModule(id: ModuleId): Option[ResolvedGeneratorModule] = generatorModules.find(_.module == id)
