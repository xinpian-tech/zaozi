package me.jiuyang.syntheke

enum NodeDirection derives CanEqual:
  case Inward, Outward

/** A node's place in one domain kind: a declared domain or another node's domain, or, with no source, the domain
  * its carrier bind delivers.
  */
final case class Membership private[syntheke] (kind: DomainKind, source: Option[DomainSource[?]], loc: SourceLoc)

/** How a node's parameter is computed: a constant, or a function of the parameters of opposite nodes of its module. */
private[syntheke] enum NodeComputation:
  case Constant(value: Any)
  case Derived(sources: Vector[ModuleNodeId], compute: Map[ModuleNodeId, Any] => Either[Violation, Any])

  /** The nodes whose parameters this one reads. */
  def reads: Vector[ModuleNodeId] = this match
    case Constant(_)         => Vector.empty
    case Derived(sources, _) => sources

final case class NodeSpec private[syntheke] (
  name:                              String,
  direction:                         NodeDirection,
  protocol:                          Protocol,
  private[syntheke] val computation: NodeComputation,
  memberships:                       Vector[Membership],
  order:                             Int,
  loc:                               SourceLoc)

final class ProbeSpec[P] private[syntheke] (
  val node:                      ProbeNode[P],
  private[syntheke] val selector: ProbeSelector[?, P],
  val loc:                       SourceLoc)

sealed trait ModuleSpec:
  def id:  ModuleId
  def loc: SourceLoc

private[syntheke] sealed trait NodeModuleSpec extends ModuleSpec:
  def nodes: Vector[NodeSpec]
  def node(name: String): Option[NodeSpec] = nodes.find(_.name == name)

/** The boundary of a design: its external nodes, or those of a frozen design as an instantiating design sees them. */
private[syntheke] final case class BoundaryModuleSpec(
  id:       ModuleId,
  target:   ModuleId,
  external: Boolean,
  nodes:    Vector[NodeSpec],
  loc:      SourceLoc)
    extends NodeModuleSpec

/** `definition` is the design and module path the wrapper was declared at; instances of one frozen design share it. */
final case class WrapperModuleSpec private[syntheke] (
  id:                               ModuleId,
  moduleName:                       String,
  children:                         Vector[String],
  loc:                              SourceLoc,
  private[syntheke] val definition: AnyRef)
    extends ModuleSpec

final case class GeneratorModuleSpec private[syntheke] (
  id:                               ModuleId,
  definition:                       GeneratorDefinition[?],
  nodes:                            Vector[NodeSpec],
  private[syntheke] val parameters: (EdgeView, DomainGraph) => Either[Violation, Any],
  loc:                              SourceLoc,
  probes:                           Vector[ProbeSpec[?]])
    extends NodeModuleSpec

final case class BindDecl private[syntheke] (
  source:     ModuleNodeId,
  target:     ModuleNodeId,
  declaredIn: ModuleId,
  loc:        SourceLoc):
  def id: BindId = BindId(source, target)

final case class DesignSpec private[syntheke] (
  modules:                          Map[ModuleId, ModuleSpec],
  moduleOrder:                      Vector[ModuleId],
  binds:                            Vector[BindDecl],
  domains:                          Vector[Domain[?]],
  root:                             ModuleId,
  private[syntheke] val boundaries: Vector[Boundary[?]]):

  def wrapper(id: ModuleId): Option[WrapperModuleSpec] = modules.get(id).collect { case w: WrapperModuleSpec => w }

  def generatorModule(id: ModuleId): Option[GeneratorModuleSpec] =
    modules.get(id).collect { case g: GeneratorModuleSpec => g }

  def generatorModules: Vector[GeneratorModuleSpec] = moduleOrder.flatMap(generatorModule)

  private[syntheke] def nodeModules: Vector[NodeModuleSpec] =
    moduleOrder.flatMap(id => modules.get(id).collect { case n: NodeModuleSpec => n })

  def nodeSpec(id: ModuleNodeId): Option[NodeSpec] =
    modules.get(id.module).collect { case n: NodeModuleSpec => n }.flatMap(_.node(id.name))

  def generators: Vector[GeneratorDefinition[?]] =
    generatorModules.map(_.definition).foldLeft(Vector.empty[GeneratorDefinition[?]]) { (acc, e) =>
      if acc.exists(_ eq e) then acc else acc :+ e
    }
