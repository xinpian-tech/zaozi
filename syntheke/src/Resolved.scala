package me.jiuyang.syntheke


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

final case class NodeView private[syntheke] (
  node:                             ModuleNodeId,
  direction:                        NodeDirection,
  edge:                             ResolvedEdge)

final case class EdgeView private[syntheke] (
  module: ModuleId,
  nodes:  Vector[NodeView]):

  private[syntheke] def lookupEdge(n: Port[?]): n.protocol.Edge =
    val view = nodes.find(_.node == n.id)
    require(view.isDefined, s"node ${n.id.show} is not a node of EdgeView of ${module.show}")
    view.get.edge.edgeAs(n.protocol)

final case class ResolvedGeneratorModule private[syntheke] (
  module:           ModuleId,
  definition:       GeneratorDefinition[?],
  view:             EdgeView,
  fullParam:        Any,
  probeDeclaration: ProbeDeclaration)


final case class PortName(segments: Vector[String], private val literalName: Option[String] = None):
  def ++(that: PortName): PortName = PortName(segments ++ that.segments)
  def encoded:            String   = literalName.getOrElse(segments.map(PortName.escape).mkString("_"))

object PortName:
  private[syntheke] def literal(name: String):  PortName = PortName(Vector(name), Some(name))
  private def escape(segment: String):          String   =
    segment.replace("$", "$$").replace("_", "$u").replace("-", "$m")

  private[syntheke] def dangle(m: ModuleId, endpoint: ModuleId, base: PortName): PortName =
    PortName(endpoint.path.drop(m.path.length).flatMap(inst => Vector("inst", inst))) ++ base

  private[syntheke] def probeBase(portName: String): PortName = PortName(Vector("probe", portName, "out"))

enum PortDirection derives CanEqual:
  case Input, Output

enum PlanOrigin derives CanEqual:
  case Design(bind: BindId)
  case Verification(source: ModuleNodeId)
  case ProbeRead(source: ModuleNodeId)
  case Observation(source: ModuleNodeId)

final case class PortPlan private[syntheke] (
  module:    ModuleId,
  direction: PortDirection,
  name:      PortName,
  interface: ProtocolInterface,
  origin:    PlanOrigin,
  loc:       SourceLoc)

enum LocalEndpoint derives CanEqual:
  case ThisPort(name: PortName)

  case ChildPort(instance: String, port: PortName)

final case class WirePlan private[syntheke] (
  module: ModuleId,
  from:   LocalEndpoint,
  to:     LocalEndpoint,
  origin: PlanOrigin,
  loc:    SourceLoc)

final case class LayerTree(children: Map[String, LayerTree]):
  def merge(that: LayerTree): LayerTree =
    LayerTree(
      (children.keySet ++ that.children.keySet).map { k =>
        k -> children.getOrElse(k, LayerTree.empty).merge(that.children.getOrElse(k, LayerTree.empty))
      }.toMap
    )
  def add(path: LayerPath):   LayerTree = merge(LayerTree.of(path))
  def isEmpty:                Boolean   = children.isEmpty

  def paths(prefix: Vector[String] = Vector.empty): Vector[Vector[String]] =
    children.toVector.sortBy(_._1).flatMap { (name, sub) =>
      (prefix :+ name) +: sub.paths(prefix :+ name)
    }

object LayerTree:
  val empty:               LayerTree = LayerTree(Map.empty)
  private def of(path: LayerPath): LayerTree =
    path.segments.foldRight(empty)((seg, sub) => LayerTree(Map(seg -> sub)))

/** One design negotiated on its own, before it is joined with the frozen designs it instantiates. */
private[syntheke] final case class Negotiated(
  spec:             DesignSpec,
  domains:          DomainGraph,
  edges:            Vector[ResolvedEdge],
  generatorModules: Vector[ResolvedGeneratorModule],
  probes:           ProbeCatalog,
  observations:     Map[ModuleId, ProbeBindings]):
  def edgeAt(node: ModuleNodeId): ResolvedEdge = edges.find(e => e.bind.source == node || e.bind.target == node).get

final case class ResolvedDesign private[syntheke] (
  spec:              DesignSpec,
  domains:           DomainGraph,
  edges:             Vector[ResolvedEdge],
  generatorModules:  Vector[ResolvedGeneratorModule],
  portPlans:         Vector[PortPlan],
  wirePlans:         Vector[WirePlan],
  layerDecls:        Map[ModuleId, LayerTree],
  probes:            ProbeCatalog,
  observations: Map[ModuleId, ProbeBindings],
  private[syntheke] val dependencies: Vector[(ModuleId, ResolvedDesign)]):
  private[syntheke] def boundaryEdge(boundary: Boundary[?]): ResolvedEdge =
    require(spec.boundaries.exists(_.terminal eq boundary.terminal), "boundary is not public in this design")
    edgeAt(boundary.terminal.id)

  /** The domain of `kind` the outside of a boundary is in. */
  private[syntheke] def boundaryDomain[K <: DomainKind](boundary: Boundary[?], kind: K): Settled[K] =
    boundaryEdge(boundary)
    domains
      .member(boundary.terminal.id, kind)
      .getOrElse(throw IllegalArgumentException(s"boundary ${boundary.id.show} is in no ${kind.name} domain"))
  def edgeAt(node: ModuleNodeId): ResolvedEdge =
    val found = edges.find(e => e.bind.source == node || e.bind.target == node)
    require(found.isDefined, s"${node.show} has no settled edge")
    found.get

  def generatorModule(id: ModuleId): Option[ResolvedGeneratorModule] = generatorModules.find(_.module == id)
