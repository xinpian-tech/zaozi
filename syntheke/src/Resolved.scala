package me.jiuyang.syntheke


final case class ResolvedEdge(
  bind:     BindId,
  protocol: Protocol,
  down:     Any,
  up:       Any,
  edge:     Any,
  interface: ProtocolInterface.Bundle):
  def edgeAs(p: Protocol): p.Edge =
    require(p eq protocol, s"${bind.show}: read with a protocol object other than the edge's own")
    edge.asInstanceOf[p.Edge]

final case class NodeView(
  node:                             ModuleNodeId,
  direction:                        NodeDirection,
  edge:                             ResolvedEdge,
  private[syntheke] val capability: NodeCapability)

final case class EdgeView private[syntheke] (
  module:                      ModuleId,
  private[syntheke] val owner: DesignOwner,
  nodes: Vector[NodeView]):

  def edgeOf(n: Port[?]): n.protocol.Edge =
    require(n.owner eq owner, s"node ${n.id.show} belongs to another Design build")
    val view = nodes.find(_.node == n.id)
    require(view.isDefined, s"node ${n.id.show} is not a node of EdgeView of ${module.show}")
    require(
      view.get.capability eq n.capability,
      s"node ${n.id.show} is not the live incarnation represented by EdgeView of ${module.show}"
    )
    view.get.edge.edgeAs(n.protocol)

final class DomainView private[syntheke] (
  val module: ModuleId,
  owner:      DesignOwner,
  entries: Vector[(DomainReadable[?], ResolvedDomain)]):
  private val byKey: Map[DomainReadable[?], ResolvedDomain] = entries.toMap

  private def entry[D <: Domain](token: DomainReadable[D]): ResolvedDomain =
    require(token.owner eq owner, "domain token belongs to another Design build")
    val found = byKey.get(token)
    require(found.isDefined, "domain token is not present in this DomainView")
    require(found.get.domain eq token.domain, s"domain token ${token.domain.key.show} does not match its resolved entry")
    found.get

  def value[D <: Domain](token: DomainReadable[D]): token.domain.Value =
    entry(token).value.asInstanceOf[token.domain.Value]

  def sameIdentity[D <: Domain](a: DomainReadable[D], b: DomainReadable[D]): Boolean =
    require(a.domain eq b.domain, s"sameIdentity operands use different domain objects for ${a.domain.key.show}")
    entry(a).id == entry(b).id

final class EdgeDomains private[syntheke] (
  outwardTokens: Vector[NodeDomain[?]],
  inwardTokens: Vector[NodeDomain[?]],
  values: DomainView):
  private def token[D <: Domain](tokens: Vector[NodeDomain[?]], domain: D): NodeDomain[D] =
    tokens
      .find(_.domain eq domain)
      .getOrElse(throw IllegalArgumentException(s"edge endpoint has no ${domain.key.show} domain"))
      .asInstanceOf[NodeDomain[D]]

  def outward[D <: Domain](domain: D): NodeDomain[D] = token(outwardTokens, domain)
  def inward[D <: Domain](domain: D): NodeDomain[D] = token(inwardTokens, domain)
  def value[D <: Domain](source: NodeDomain[D]): source.domain.Value = values.value(source)

enum DomainContributor:
  case Node(node: ModuleNodeId)
  case Declaration(id: DomainDeclId)
  case Module(module: ModuleId)
  case Bind(bind: BindId)

final case class ResolvedDomainRequirement(
  source:  DomainContributor,
  value:   Any,
  encoded: ujson.Value)

final case class ResolvedDomain(
  id:           DomainDeclId,
  domain:       Domain,
  value:        Any,
  encodedValue: ujson.Value,
  predecessors: Vector[DomainDeclId],
  requirements: Vector[ResolvedDomainRequirement],
  loc: (sourcecode.File, sourcecode.Line))

final case class ResolvedDomainAttachment(
  key:         NodeDomainKey,
  declaration: DomainDeclId,
  provenance:  AttachmentProvenance)

final case class ResolvedDomainCheck(
  subject:    String,
  domainKeys: Vector[DomainKey])

final case class ResolvedGeneratorModule(
  module:           ModuleId,
  definition:       GeneratorDefinition[?],
  view:             EdgeView,
  domainView:       DomainView,
  fullParam:        Any,
  encodedFullParam: ujson.Value,
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

final case class PortPlan(
  module:    ModuleId,
  direction: PortDirection,
  name:      PortName,
  interface: ProtocolInterface,
  origin:    PlanOrigin,
  loc:       (sourcecode.File, sourcecode.Line))

enum LocalEndpoint derives CanEqual:
  case ThisPort(name: PortName)

  case ChildPort(instance: String, port: PortName)

final case class WirePlan(
  module: ModuleId,
  from:   LocalEndpoint,
  to:     LocalEndpoint,
  origin: PlanOrigin,
  loc:    (sourcecode.File, sourcecode.Line))

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

final case class ResolvedDesign(
  spec:              DesignSpec,
  domains:           Vector[ResolvedDomain],
  domainAttachments: Vector[ResolvedDomainAttachment],
  domainChecks:   Vector[ResolvedDomainCheck],
  constraints:      Vector[ConstraintSpec],
  edges:             Vector[ResolvedEdge],
  generatorModules:  Vector[ResolvedGeneratorModule],
  portPlans:         Vector[PortPlan],
  wirePlans:         Vector[WirePlan],
  layerDecls:        Map[ModuleId, LayerTree],
  probes:            ProbeCatalog,
  observations: ProbeBindings):
  def edgeAt(node: ModuleNodeId): ResolvedEdge =
    val found = edges.find(e => e.bind.source == node || e.bind.target == node)
    require(found.isDefined, s"${node.show} has no settled edge")
    found.get

  def generatorModule(id: ModuleId): Option[ResolvedGeneratorModule] = generatorModules.find(_.module == id)
