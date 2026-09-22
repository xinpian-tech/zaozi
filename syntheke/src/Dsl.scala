package me.jiuyang.syntheke

import upickle.default.Writer

sealed trait BuildMode
sealed trait WrapperMode       extends BuildMode
sealed trait GeneratorMode[FP] extends BuildMode

type WrapperScope       = BuildContext[WrapperMode]
type GeneratorScope[FP] = BuildContext[GeneratorMode[FP]]

final class Design[A] private[syntheke] (
  private[syntheke] val moduleName: String,
  private[syntheke] val body: WrapperScope ?=> (A, Vector[Constraint]),
  private[syntheke] val loc: (sourcecode.File, sourcecode.Line))(
  using private[syntheke] val dangles: Dangles[A]):
  private[syntheke] lazy val frozen: (ResolvedDesign, A) = Negotiator.build(this)

object Design:
  def apply[A: Dangles](moduleName: String)(
    body: WrapperScope ?=> (A, Vector[Constraint])
  )(using file: sourcecode.File, line: sourcecode.Line): Design[A] =
    new Design(moduleName, body, (file, line))

final class DesignInstance[A] private[syntheke] (val ports: A, private[syntheke] val resolved: ResolvedDesign)

extension (instance: DesignInstance[?])
  def probes: ProbeCatalog = instance.resolved.probes

  def edgeOf[P <: Protocol](boundary: Boundary[P]): boundary.protocol.Edge =
    instance.resolved.boundaryEdge(boundary).edgeAs(boundary.protocol)

  def value[P <: Protocol, D <: Domain](boundary: Boundary[P], domain: D): domain.Value =
    instance.resolved.boundaryDomain(boundary, domain).value.asInstanceOf[domain.Value]

extension [A](design: Design[A])
  def instantiate(using scope: WrapperScope, name: sourcecode.Name): DesignInstance[A] =
    scope.instantiate(design, name.value)

def wrapper[A: Dangles](
  moduleName: String
)(body:       WrapperScope ?=> (A, Vector[Constraint])
)(
  using
  ws:         WrapperScope,
  name:       sourcecode.Name,
  file:       sourcecode.File,
  line:       sourcecode.Line
): A = ws.wrapper(name.value, moduleName)(body)

def generator[FP]: GeneratorCall[FP] = new GeneratorCall[FP]

extension [D <: Domain](domain: D)
  def declare(
    value:       domain.Value,
    requirement: Option[domain.Requirement]
  )(
    using
    context: BuildContext[?],
    name:    sourcecode.Name,
    file:    sourcecode.File,
    line:    sourcecode.Line
  ): DomainHandle[D] =
    context.declareDomain(domain, name.value, Vector.empty, _ => Right((value, requirement)), (file, line))

extension (sources: DomainReadable[?] | scala.collection.Seq[DomainReadable[?]])
  def derive[D <: Domain](
    domain:  D
  )(compute: DomainView => Either[Violation, (domain.Value, Option[domain.Requirement])]
  )(
    using
    context: BuildContext[?],
    name:    sourcecode.Name,
    file:    sourcecode.File,
    line:    sourcecode.Line
  ): DomainHandle[D] =
    context.declareDomain(domain, name.value, sources, compute, (file, line))

extension [D <: Domain](handle: DomainHandle[D])
  def provide[R <: BuildMode, A](
    body:          BuildContext[R] ?=> A
  )(
    using context: BuildContext[R]
  ): A = context.withDomain(handle)(body)

extension [D <: Domain](source: DomainReadable[D])
  def requirement(
    value: source.domain.Requirement
  )(
    using file: sourcecode.File,
    line: sourcecode.Line
  ): Constraint = new Constraint.Required(source)(value, (file, line))

extension (sources: scala.collection.Seq[DomainReadable[?]])
  def check(
    run: DomainView => Either[Violation, Unit]
  )(
    using file: sourcecode.File,
    line: sourcecode.Line
  ): Constraint = new Constraint.Check(sources.toVector.distinct, run, (file, line))

def probe[FP, P: TypeIdentity: Writer](
  selector: ProbeSelector[FP, P]
)(
  using gs: GeneratorScope[FP],
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): ProbeNode[P] = gs.declareProbe(selector)(name.value, (file, line))

def inward[FP](
  p:    Protocol
)(domains: (Domain | DomainReadable[?])*
)(
  using
  gs:   GeneratorScope[FP],
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): p.InwardDraft = gs.inward(p)(domains)(name.value)

def outward[FP](
  p:    Protocol
)(domains: (Domain | DomainReadable[?])*
)(
  using
  gs:   GeneratorScope[FP],
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): p.OutwardDraft = gs.outward(p)(domains)(name.value)

def parameters[FP](
  compute:  (EdgeView, DomainView) => Either[Violation, FP]
)(
  using gs: GeneratorScope[FP]
): Unit = gs.parameters(compute)

extension [P <: Protocol](target: InwardPort[P])
  infix def <--(
    source: OutwardPort[P]
  )(
    using
    ws:     WrapperScope,
    file:   sourcecode.File,
    line:   sourcecode.Line
  ): Unit =
    ws.recordBind(source, target, (file, line))

final class GeneratorCall[FP] private[syntheke] ():
  def apply[A: Dangles](
    body:       GeneratorScope[FP] ?=> (A, Vector[Constraint])
  )(
    using
    definition: GeneratorDefinition[FP],
    ws:         WrapperScope,
    name:       sourcecode.Name,
    file:       sourcecode.File,
    line:       sourcecode.Line
  ): A = ws.generator(name.value, definition)(body)

extension [P <: Protocol](node: NodeHandle[P])
  def domain[D <: Domain](domain: D): NodeDomain[D] = node.lookupDomain(domain)

extension [P <: Protocol](node: InwardNodeDraft[P])
  inline def derive[S](sources: S)(
    compute: ParameterInputs.Values[S] => Either[Violation, (node.protocol.Up, Vector[Constraint])]
  ): InwardPort[P] =
    ParameterInputs.validate[S](false)
    ParameterInputs.inward(node, sources, compute)

  def fixed(value: node.protocol.Up): InwardPort[P] = node.scope.fixed(node, value)

extension [P <: Protocol](node: OutwardNodeDraft[P])
  inline def derive[S](sources: S)(
    compute: ParameterInputs.Values[S] => Either[Violation, (node.protocol.Down, Vector[Constraint])]
  ): OutwardPort[P] =
    ParameterInputs.validate[S](true)
    ParameterInputs.outward(node, sources, compute)

  def fixed(value: node.protocol.Down): OutwardPort[P] = node.scope.fixed(node, value)

extension (view: EdgeView)
  def edgeOf(node: Port[?]): node.protocol.Edge = view.lookupEdge(node)

extension (view: DomainView)
  def value[D <: Domain](source: DomainReadable[D]): source.domain.Value = view.lookupValue(source)

  def sameIdentity[D <: Domain](a: DomainReadable[D], b: DomainReadable[D]): Boolean = view.compareIdentity(a, b)

extension (domains: EdgeDomains)
  def inward[D <: Domain](domain: D): NodeDomain[D] = domains.inwardToken(domain)

  def outward[D <: Domain](domain: D): NodeDomain[D] = domains.outwardToken(domain)

  def value[D <: Domain](source: NodeDomain[D]): source.domain.Value = domains.lookupValue(source)

extension (catalog: ProbeCatalog)
  def query[P: TypeIdentity]: Vector[ResolvedProbe[P]] = catalog.matching[P]

extension [P <: Protocol](node: InwardPort[P])
  def boundary(externalParams: node.protocol.Down)(externalDomains: (Domain | DomainReadable[?])*)(
    using scope: WrapperScope, name: sourcecode.Name, file: sourcecode.File, line: sourcecode.Line
  ): InwardBoundary[P] = scope.inwardBoundary(node, externalParams, externalDomains, name.value, (file, line))

  infix def <--(source: OutwardBoundary[P])(
    using scope: WrapperScope, file: sourcecode.File, line: sourcecode.Line
  ): Unit = scope.connectBoundary(source, node, (file, line))

extension [P <: Protocol](node: OutwardPort[P])
  def boundary(externalParams: node.protocol.Up)(externalDomains: (Domain | DomainReadable[?])*)(
    using scope: WrapperScope, name: sourcecode.Name, file: sourcecode.File, line: sourcecode.Line
  ): OutwardBoundary[P] = scope.outwardBoundary(node, externalParams, externalDomains, name.value, (file, line))

extension [P <: Protocol](target: InwardBoundary[P])
  def boundary(using scope: WrapperScope, name: sourcecode.Name,
    file: sourcecode.File, line: sourcecode.Line): InwardBoundary[P] =
    scope.forwardBoundary(target, name.value, (file, line)).asInstanceOf[InwardBoundary[P]]

  infix def <--(source: OutwardPort[P])(
    using scope: WrapperScope, file: sourcecode.File, line: sourcecode.Line
  ): Unit = scope.connectBoundary(target, source, (file, line))

  infix def <--(source: OutwardBoundary[P])(
    using scope: WrapperScope, file: sourcecode.File, line: sourcecode.Line
  ): Unit = scope.connectBoundaries(source, target, (file, line))

extension [P <: Protocol](source: OutwardBoundary[P])
  def boundary(using scope: WrapperScope, name: sourcecode.Name,
    file: sourcecode.File, line: sourcecode.Line): OutwardBoundary[P] =
    scope.forwardBoundary(source, name.value, (file, line)).asInstanceOf[OutwardBoundary[P]]

extension [P](probe: ResolvedProbe[P])
  def boundary(using scope: WrapperScope): ResolvedProbe[P] = scope.forwardProbe(probe)

extension [P <: Protocol](boundary: Boundary[P])
  def domain[D <: Domain](domain: D)(using scope: BuildContext[?]): NodeDomain[D] =
    scope.boundaryDomain(boundary, domain)
