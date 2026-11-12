package me.jiuyang.syntheke

import upickle.default.Writer

/** What a kind of probe publishes, with the parameters each probe of it carries. A catalog finds probes by their
  * contract; contracts compare by identity, so one contract object stands for one kind of probe.
  */
abstract class ProbeContract[P](
  using private[syntheke] val parameterWriter: Writer[P])

final class ProbeNode[P] private[syntheke] (val id: ModuleNodeId, val contract: ProbeContract[P])

/** What a backend keeps about a selected probe so that an observer can read it. */
trait ProbeImplementation

final case class ProbeResolution[P](parameters: P, portName: String, implementation: ProbeImplementation)

/** Picks, from a generator's full parameter and public probe declaration, the port a probe node stands for. */
trait ProbeSelector[FP, P]:
  def contract:                                              ProbeContract[P]
  def resolve(fullParam: FP, declaration: ProbeDeclaration): Either[Violation, Option[ProbeResolution[P]]]

final class ResolvedProbe[P] private[syntheke] (
  private[syntheke] val node: ProbeNode[P],
  val parameters:             P,
  val port:                   ResolvedPublicPort,
  val implementation: ProbeImplementation):
  def id: ModuleNodeId = node.id

object ResolvedProbe:
  private[syntheke] def encode[P](resolved: ResolvedProbe[P]): ujson.Value =
    ujson.Obj(
      "node"       -> upickle.default.writeJs(resolved.node.id),
      "parameters" -> upickle.default.writeJs(resolved.parameters)(
        using resolved.node.contract.parameterWriter
      ),
      "port"       -> upickle.default.writeJs(resolved.port.id),
      "reference"  -> upickle.default.writeJs(resolved.port.reference)
    )

final case class ProbePort(name: String, tpe: ProtocolInterface.Probe):
  require(name.nonEmpty, "a public Probe port name cannot be empty")

trait ProbeDeclaration:
  def ports: Vector[ProbePort]

/** A public probe port of a generator module, where its probes are read from. */
final case class ResolvedPublicPort private[syntheke] (id: ModuleNodeId, reference: ProtocolInterface.Probe):
  /** The input port of an observer this probe arrives at. */
  def portName: String = PortName.dangle(ModuleId.root, id.module, PortName.probeBase(id.name)).encoded

object ResolvedPublicPort:
  given upickle.default.Writer[ResolvedPublicPort] = upickle.default.writer[ujson.Value].comap { port =>
    ujson.Obj(
      "source"    -> upickle.default.writeJs(port.id),
      "portName"  -> port.portName,
      "reference" -> upickle.default.writeJs(port.reference)
    )
  }

/** What an observer reads: the public probe ports it observes, each arriving at the input port named after it. */
final class ProbeBindings private[syntheke] (val sources: Vector[ResolvedPublicPort])

object ProbeBindings:
  private[syntheke] val empty: ProbeBindings = new ProbeBindings(Vector.empty)

  def from(sources: Seq[ResolvedPublicPort]): ProbeBindings = new ProbeBindings(sources.toVector.distinct)

  given upickle.default.Writer[ProbeBindings] = upickle.default.writer[Vector[ResolvedPublicPort]].comap(_.sources)

/** The probes a design publishes, and the public ports they are read from. */
final class ProbeCatalog private[syntheke] (private[syntheke] val nodes: Vector[ResolvedProbe[?]]):
  private[syntheke] def ports: Vector[ResolvedPublicPort] = nodes.map(_.port).distinct

  private[syntheke] def combined(that: ProbeCatalog): ProbeCatalog = new ProbeCatalog((nodes ++ that.nodes).distinct)

  private[syntheke] def published(local: Vector[ProbeNode[?]], forwarded: Vector[ResolvedProbe[?]]): ProbeCatalog =
    new ProbeCatalog((nodes.filter(p => local.contains(p.node)) ++ forwarded).distinct)

  private[syntheke] def matching[P](contract: ProbeContract[P]): Vector[ResolvedProbe[P]] =
    nodes.collect { case resolved if resolved.node.contract eq contract => resolved.asInstanceOf[ResolvedProbe[P]] }

  /** The observed ports that are not in this catalog. */
  private[syntheke] def foreign(observations: ProbeBindings): Vector[ResolvedPublicPort] =
    observations.sources.filterNot(ports.contains)
