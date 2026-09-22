package me.jiuyang.syntheke


enum NodeDirection derives CanEqual:
  case Inward, Outward

sealed trait DomainSelectorSpec
object DomainSelectorSpec:
  private[syntheke] final case class Frozen(source: DomainReadable[?], provenance: AttachmentProvenance) extends DomainSelectorSpec
  final case class Direct(private[syntheke] val domain: DomainHandle[?]) extends DomainSelectorSpec
  final case class Contextual(
    private[syntheke] val domain: DomainHandle[?],
    providedAt:                  ModuleId)
      extends DomainSelectorSpec
  final case class Follow(use: NodeDomain[?]) extends DomainSelectorSpec
  final case class CarrierOut(private[syntheke] val source: DomainReadable[?]) extends DomainSelectorSpec
  case object CarrierIn extends DomainSelectorSpec

final case class NodeDomainSpec(
  key:                              NodeDomainKey,
  domain:                             Domain,
  selector:                         DomainSelectorSpec,
  order:                            Int,
  loc:                              (sourcecode.File, sourcecode.Line),
  private[syntheke] val capability: NodeDomain[?])

final case class ConstraintSpec(
  source: DomainContributor,
  constraint: Constraint,
  order: Int):
  private[syntheke] def reads: Vector[DomainReadable[?]] = constraint.reads
  private[syntheke] def loc: (sourcecode.File, sourcecode.Line) = constraint.loc

private[syntheke] enum NodeComputation:
  case Constant(value: Any)
  case Derived(plan: ReadPlan, compute: ReadValues => Either[Violation, (Any, Vector[Constraint])])

  def readPlan: ReadPlan = this match
    case Constant(_)      => ReadPlan()
    case Derived(plan, _) => plan

  def apply(values: ReadValues): Either[Violation, (Any, Vector[Constraint])] = this match
    case Constant(value)     => Right((value, Vector.empty))
    case Derived(_, compute) => compute(values)

final case class NodeSpec(
  name:                              String,
  direction:                         NodeDirection,
  protocol:                          Protocol,
  private[syntheke] val computation: NodeComputation,
  nodeDomains:                        Vector[NodeDomainSpec],
  order:                             Int,
  loc:                               (sourcecode.File, sourcecode.Line),
  private[syntheke] val capability: NodeCapability)

final case class ParamDependencySpec(
  from:  String,
  to:    String,
  order: Int,
  loc:   (sourcecode.File, sourcecode.Line))

final class ProbeSpec[P] private[syntheke] (
  val node: ProbeNode[P],
  private[syntheke] val resolve: (Any, ProbeDeclaration) => Either[Violation, Option[ProbeResolution[P]]],
  val loc: (sourcecode.File, sourcecode.Line))

sealed trait ModuleSpec:
  def id:  ModuleId
  def loc: (sourcecode.File, sourcecode.Line)

private[syntheke] sealed trait NodeModuleSpec extends ModuleSpec:
  def nodes: Vector[NodeSpec]
  def dependencies: Vector[ParamDependencySpec]
  def node(name: String): Option[NodeSpec] = nodes.find(_.name == name)

private[syntheke] final case class BoundaryModuleSpec(
  id: ModuleId,
  target: ModuleId,
  external: Boolean,
  nodes: Vector[NodeSpec],
  loc: (sourcecode.File, sourcecode.Line)) extends NodeModuleSpec:
  val dependencies: Vector[ParamDependencySpec] = Vector.empty

final case class WrapperModuleSpec(
  id:         ModuleId,
  moduleName: String,
  children:   Vector[String],
  loc:        (sourcecode.File, sourcecode.Line),
  private[syntheke] val definition: AnyRef)
    extends ModuleSpec

final case class GeneratorModuleSpec(
  id:                               ModuleId,
  definition:                       GeneratorDefinition[?],
  nodes:                            Vector[NodeSpec],
  dependencies:                     Vector[ParamDependencySpec],
  private[syntheke] val parameters: (EdgeView, DomainView) => Either[Violation, Any],
  loc:                              (sourcecode.File, sourcecode.Line),
  probes: Vector[ProbeSpec[?]])
    extends NodeModuleSpec

final case class BindDecl(
  order:      Int,
  source:     ModuleNodeId,
  target:     ModuleNodeId,
  declaredIn: ModuleId,
  loc: (sourcecode.File, sourcecode.Line)):
  def bindId: BindId = BindId(order, source, target)

final case class DesignSpec(
  private[syntheke] val owner: DesignOwner,
  modules:                     Map[ModuleId, ModuleSpec],
  moduleOrder:                 Vector[ModuleId],
  binds:                       Vector[BindDecl],
  domainDecls:                 Vector[DomainHandle[?]],
  constraints:                 Vector[ConstraintSpec],
  root: ModuleId,
  private[syntheke] val boundaries: Vector[Boundary[?]]):

  def wrapper(id:    ModuleId):         Option[WrapperModuleSpec]   = modules.get(id).collect { case w: WrapperModuleSpec => w }
  def generatorModule(id: ModuleId):    Option[GeneratorModuleSpec] =
    modules.get(id).collect { case g: GeneratorModuleSpec => g }
  def generatorModules:                 Vector[GeneratorModuleSpec] =
    moduleOrder.flatMap(generatorModule)
  private[syntheke] def nodeModules: Vector[NodeModuleSpec] = moduleOrder.flatMap(id => modules.get(id).collect { case n: NodeModuleSpec => n })
  def nodeSpec(id: ModuleNodeId):       Option[NodeSpec]            =
    modules.get(id.module).collect { case n: NodeModuleSpec => n }.flatMap(_.node(id.name))
  def domainDecl(id: DomainDeclId):     Option[DomainHandle[?]]     = domainDecls.find(_.id == id)
  def nodeDomains:                       Vector[NodeDomainSpec]   = nodeModules.flatMap(_.nodes.flatMap(_.nodeDomains))

  def generators: Vector[GeneratorDefinition[?]] =
    generatorModules.map(_.definition).foldLeft(Vector.empty[GeneratorDefinition[?]]) { (acc, e) =>
      if acc.exists(_ eq e) then acc else acc :+ e
    }
