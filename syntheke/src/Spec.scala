package me.jiuyang.syntheke

import scala.collection.immutable.VectorMap

enum NodeDirection derives CanEqual:
  case Inward, Outward

/** How a node's parameter is computed: a constant, or a function of the parameters of opposite nodes of its module. */
private[syntheke] final case class NodeComputation(
  reads:   Vector[ModuleNodeId],
  compute: VectorMap[ModuleNodeId, Any] => Either[Violation, Any])

private[syntheke] object NodeComputation:
  def constant(value: Any): NodeComputation = NodeComputation(Vector.empty, _ => Right(value))

final case class NodeSpec private[syntheke] (
  name:                              String,
  direction:                         NodeDirection,
  protocol:                          Protocol,
  private[syntheke] val computation: NodeComputation,
  domains:                           Vector[Domain[?]],
  loc:                               SourceLoc)

final class ProbeSpec[P] private[syntheke] (
  val node:                       ProbeNode[P],
  private[syntheke] val selector: ProbeSelector[?, P],
  val loc:                        SourceLoc)

sealed trait ModuleSpec:
  def id:  ModuleId
  def loc: SourceLoc

private[syntheke] sealed trait NodeModuleSpec extends ModuleSpec:
  def nodes:              Vector[NodeSpec]
  def node(name: String): Option[NodeSpec] = nodes.find(_.name == name)

/** Constant nodes standing for one side of a design boundary: the outside of this design's own boundaries, or, with
  * `imported`, a frozen design this one instantiates, whose boundaries face the instantiating design.
  */
private[syntheke] final case class BoundaryModuleSpec(
  id:       ModuleId,
  target:   ModuleId,
  imported: Boolean,
  nodes:    Vector[NodeSpec],
  loc:      SourceLoc)
    extends NodeModuleSpec

final case class WrapperModuleSpec private[syntheke] (
  id:         ModuleId,
  moduleName: String,
  children:   Vector[String],
  loc:        SourceLoc)
    extends ModuleSpec

final case class GeneratorModuleSpec private[syntheke] (
  id:                               ModuleId,
  definition:                       GeneratorDefinition[?],
  nodes:                            Vector[NodeSpec],
  private[syntheke] val parameters: EdgeView => Either[Violation, Any],
  loc:                              SourceLoc,
  probes:                           Vector[ProbeSpec[?]])
    extends NodeModuleSpec

final case class BindDecl private[syntheke] (
  source:     ModuleNodeId,
  target:     ModuleNodeId,
  declaredIn: ModuleId,
  loc: SourceLoc):
  def id: BindId = BindId(source, target)

final case class DesignSpec private[syntheke] (
  modules: VectorMap[ModuleId, ModuleSpec],
  binds:   Vector[BindDecl],
  domains: Vector[Domain[?]],
  root:    ModuleId,
  private[syntheke] val boundaries: Vector[Boundary[?]]):

  def wrapper(id: ModuleId): Option[WrapperModuleSpec] = modules.get(id).collect { case w: WrapperModuleSpec => w }

  def generatorModule(id: ModuleId): Option[GeneratorModuleSpec] =
    modules.get(id).collect { case g: GeneratorModuleSpec => g }

  /** The modules in order: the design's tree in preorder, then the modules standing for its boundaries. */
  def moduleOrder: Vector[ModuleId] = modules.keys.toVector

  def generatorModules: Vector[GeneratorModuleSpec] = moduleOrder.flatMap(generatorModule)

  private[syntheke] def nodeModules: Vector[NodeModuleSpec] =
    moduleOrder.flatMap(id => modules.get(id).collect { case n: NodeModuleSpec => n })

  def nodeSpec(id: ModuleNodeId): Option[NodeSpec] =
    modules.get(id.module).collect { case n: NodeModuleSpec => n }.flatMap(_.node(id.name))

  /** The domain of `kind` node `id` is in. */
  private[syntheke] def domainOf(id: ModuleNodeId, kind: DomainKind): Option[Domain[?]] =
    nodeSpec(id).flatMap(_.domains.find(_.kind eq kind))

  def generators: Vector[GeneratorDefinition[?]] =
    generatorModules.map(_.definition).foldLeft(Vector.empty[GeneratorDefinition[?]]) { (acc, e) =>
      if acc.exists(_ eq e) then acc else acc :+ e
    }
