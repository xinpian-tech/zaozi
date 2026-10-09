package me.jiuyang.syntheke

import upickle.default.Writer

sealed trait BuildMode
sealed trait WrapperMode       extends BuildMode
sealed trait GeneratorMode[FP] extends BuildMode

type WrapperScope       = BuildContext[WrapperMode]
type GeneratorScope[FP] = BuildContext[GeneratorMode[FP]]

final class Design[A] private[syntheke] (
  private[syntheke] val moduleName: String,
  private[syntheke] val body: WrapperScope ?=> A,
  private[syntheke] val loc: (sourcecode.File, sourcecode.Line))(
  using private[syntheke] val dangles: Dangles[A]):
  private[syntheke] lazy val frozen: (ResolvedDesign, A) = Negotiator.build(this)

object Design:
  def apply[A: Dangles](moduleName: String)(
    body: WrapperScope ?=> A
  )(using file: sourcecode.File, line: sourcecode.Line): Design[A] =
    new Design(moduleName, body, (file, line))

final class DesignInstance[A] private[syntheke] (val ports: A, private[syntheke] val resolved: ResolvedDesign)

extension (instance: DesignInstance[?])
  def probes: ProbeCatalog = instance.resolved.probes

  def edgeOf[P <: Protocol](boundary: Boundary[P]): boundary.protocol.Edge =
    instance.resolved.boundaryEdge(boundary).edgeAs(boundary.protocol)

  /** The settled domain a boundary of the instantiated design is in. */
  def domainOf[K <: DomainKind](boundary: Boundary[?], kind: K): Settled[K] =
    instance.resolved.boundaryDomain(boundary, kind)

extension [A](design: Design[A])
  def instantiate(using scope: WrapperScope, name: sourcecode.Name): DesignInstance[A] =
    scope.instantiate(design, name.value)

def wrapper[A: Dangles](
  moduleName: String
)(body:       WrapperScope ?=> A
)(
  using
  ws:         WrapperScope,
  name:       sourcecode.Name,
  file:       sourcecode.File,
  line:       sourcecode.Line
): A = ws.wrapper(name.value, moduleName)(body)

def generator[FP]: GeneratorCall[FP] = new GeneratorCall[FP]

extension [K <: DomainKind](kind: K)
  /** A domain that derives from nothing. */
  def root(
    value: kind.Root
  )(
    using
    context: BuildContext[?],
    name:    sourcecode.Name,
    file:    sourcecode.File,
    line:    sourcecode.Line
  ): Domain[K] = context.declareDomain(kind, name.value, Domain.Origin.Root(value), (file, line))

  /** A domain derived from `sources` through `link`. */
  def derive(
    sources: DomainSource[K]*
  )(link:    kind.Link
  )(
    using
    context: BuildContext[?],
    name:    sourcecode.Name,
    file:    sourcecode.File,
    line:    sourcecode.Line
  ): Domain[K] = context.declareDomain(kind, name.value, Domain.Origin.Derived(sources.toVector, link), (file, line))

extension (domain: Domain[?])
  /** Places every module the body creates in `domain`, for a kind that allows scoping. */
  def scope[R <: BuildMode, A](
    body:          BuildContext[R] ?=> A
  )(
    using context: BuildContext[R]
  ): A = context.scope(domain)(body)

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
)(domains: DomainSource[?]*
)(
  using
  gs:   GeneratorScope[FP],
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): p.InwardDraft = gs.inward(p)(domains)(name.value)

def outward[FP](
  p:    Protocol
)(domains: DomainSource[?]*
)(
  using
  gs:   GeneratorScope[FP],
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): p.OutwardDraft = gs.outward(p)(domains)(name.value)

def parameters[FP](
  compute:  (EdgeView, DomainGraph) => Either[Violation, FP]
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
    body:       GeneratorScope[FP] ?=> A
  )(
    using
    definition: GeneratorDefinition[FP],
    ws:         WrapperScope,
    name:       sourcecode.Name,
    file:       sourcecode.File,
    line:       sourcecode.Line
  ): A = ws.generator(name.value, definition)(body)

extension [P <: Protocol](node: InwardNodeDraft[P])
  inline def derive[S](sources: S)(
    compute: ParameterInputs.Values[S] => Either[Violation, node.protocol.Up]
  ): InwardPort[P] =
    ParameterInputs.validate[S](false)
    ParameterInputs.inward(node, sources, compute)

  def fixed(value: node.protocol.Up): InwardPort[P] = node.scope.fixed(node, value)

extension [P <: Protocol](node: OutwardNodeDraft[P])
  inline def derive[S](sources: S)(
    compute: ParameterInputs.Values[S] => Either[Violation, node.protocol.Down]
  ): OutwardPort[P] =
    ParameterInputs.validate[S](true)
    ParameterInputs.outward(node, sources, compute)

  def fixed(value: node.protocol.Down): OutwardPort[P] = node.scope.fixed(node, value)

extension (view: EdgeView)
  def edgeOf(node: Port[?]): node.protocol.Edge = view.lookupEdge(node)

extension (catalog: ProbeCatalog)
  def query[P: TypeIdentity]: Vector[ResolvedProbe[P]] = catalog.matching[P]

extension [P <: Protocol](node: InwardPort[P])
  def boundary(externalParams: node.protocol.Down)(externalDomains: DomainSource[?]*)(
    using scope: WrapperScope, name: sourcecode.Name, file: sourcecode.File, line: sourcecode.Line
  ): InwardBoundary[P] = scope.inwardBoundary(node, externalParams, externalDomains, name.value, (file, line))

  infix def <--(source: OutwardBoundary[P])(
    using scope: WrapperScope, file: sourcecode.File, line: sourcecode.Line
  ): Unit = scope.connectBoundary(source, node, (file, line))

extension [P <: Protocol](node: OutwardPort[P])
  def boundary(externalParams: node.protocol.Up)(externalDomains: DomainSource[?]*)(
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
  /** The domain an instantiated design's boundary is in, as a domain of the instantiating design. */
  def domain[K <: DomainKind](kind: K)(using scope: BuildContext[?]): Domain[K] =
    scope.boundaryDomain(boundary, kind)
