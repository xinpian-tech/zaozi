package me.jiuyang.syntheke

import upickle.default.Writer

/** What a kind of probe publishes, with the parameters each probe of it carries. A catalog finds probes by their
  * contract; contracts compare by identity, so one contract object stands for one kind of probe.
  */
abstract class ProbeContract[P](using private[syntheke] val parameterWriter: Writer[P])

final class ProbeNode[P] private[syntheke] (val id: ModuleNodeId, val contract: ProbeContract[P])

/** What a backend keeps about a selected probe so that an observer can read it. */
trait ProbeImplementation

final case class ProbeResolution[P](parameters: P, portName: String, implementation: ProbeImplementation)

/** Picks, from a generator's full parameter and public probe declaration, the port a probe node stands for. */
trait ProbeSelector[FP, P]:
  def contract: ProbeContract[P]
  def resolve(fullParam: FP, declaration: ProbeDeclaration): Either[Violation, Option[ProbeResolution[P]]]

final class ResolvedProbe[P] private[syntheke] (
  private[syntheke] val node: ProbeNode[P],
  val parameters: P,
  val port: ResolvedPublicPort,
  val implementation: ProbeImplementation):
  def id: ModuleNodeId = node.id

object ResolvedProbe:
  private[syntheke] def encode[P](resolved: ResolvedProbe[P]): ujson.Value =
    ujson.Obj(
      "node" -> upickle.default.writeJs(resolved.node.id),
      "parameters" -> upickle.default.writeJs(resolved.parameters)(using resolved.node.contract.parameterWriter),
      "port" -> upickle.default.writeJs(resolved.port.id),
      "reference" -> upickle.default.writeJs(resolved.port.reference)
    )

final case class ProbePort(name: String, tpe: ProtocolInterface.Probe):
  require(name.nonEmpty, "a public Probe port name cannot be empty")

trait ProbeDeclaration:
  def ports: Vector[ProbePort]

final class ResolvedPublicPort private[syntheke] (
  val id: ModuleNodeId,
  val reference: ProtocolInterface.Probe)

final case class ProbeBinding(
  source: ModuleNodeId,
  portName: String,
  reference: ProtocolInterface.Probe)
    derives upickle.default.ReadWriter

final class ProbeBindings private[syntheke] (
  private[syntheke] val nodes: Vector[ResolvedPublicPort],
  val ports: Vector[ProbeBinding])

object ProbeBindings:
  private[syntheke] val empty: ProbeBindings = new ProbeBindings(Vector.empty, Vector.empty)

  def from(sources: Seq[ResolvedPublicPort]): ProbeBindings =
    val nodes = sources.toVector.distinct
    val ports = nodes.map { node =>
      val name = PortName.dangle(ModuleId.root, node.id.module, PortName.probeBase(node.id.name)).encoded
      ProbeBinding(node.id, name, node.reference)
    }
    new ProbeBindings(nodes, ports)

  given upickle.default.Writer[ProbeBindings] =
    upickle.default.writer[Vector[ProbeBinding]].comap(_.ports)

final class ProbeCatalog private[syntheke] (
  private[syntheke] val ports: Vector[ResolvedPublicPort],
  private[syntheke] val nodes: Vector[ResolvedProbe[?]]):

  private[syntheke] def combined(that: ProbeCatalog): ProbeCatalog =
    new ProbeCatalog((ports ++ that.ports).distinct, (nodes ++ that.nodes).distinct)

  private[syntheke] def published(local: Set[ProbeNode[?]], forwarded: Vector[ResolvedProbe[?]]): ProbeCatalog =
    val selected = (nodes.filter(p => local(p.node)) ++ forwarded).distinct
    new ProbeCatalog(selected.map(_.port).distinct, selected)

  private[syntheke] def matching[P](contract: ProbeContract[P]): Vector[ResolvedProbe[P]] =
    nodes.collect { case resolved if resolved.node.contract eq contract => resolved.asInstanceOf[ResolvedProbe[P]] }

  private[syntheke] def mappedPorts: Vector[ResolvedPublicPort] = nodes.map(_.port).distinct

  /** The observed ports that are not in this catalog. */
  private[syntheke] def foreign(observations: ProbeBindings): Vector[ResolvedPublicPort] =
    observations.nodes.filterNot(node => ports.exists(_ eq node))

object ProbeCatalog:
  private[syntheke] def resolve(declarations: Vector[(ModuleId, ProbeDeclaration)])(
    settle: Vector[ResolvedPublicPort] => Vector[ResolvedProbe[?]]
  ): ProbeCatalog =
    val ports = declarations.flatMap { (module, declaration) =>
      declaration.ports.map(port => new ResolvedPublicPort(ModuleNodeId(module, port.name), port.tpe))
    }
    new ProbeCatalog(ports, settle(ports))
