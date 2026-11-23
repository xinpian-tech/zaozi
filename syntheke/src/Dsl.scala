package me.jiuyang.syntheke

sealed trait BuildMode
sealed trait WrapperMode       extends BuildMode
sealed trait GeneratorMode[FP] extends BuildMode

type WrapperScope       = BuildContext[WrapperMode]
type GeneratorScope[FP] = BuildContext[GeneratorMode[FP]]

private def here(
  using file: sourcecode.File,
  line:       sourcecode.Line
): SourceLoc = (file, line)

/** A design: negotiated once, the first time it is used, then frozen. */
final class Design[A] private[syntheke] (
  private[syntheke] val moduleName: String,
  private[syntheke] val body:       WrapperScope ?=> A,
  private[syntheke] val loc:        SourceLoc
)(
  using private[syntheke] val dangles: Dangles[A]):
  private[syntheke] var building = false

  private[syntheke] lazy val frozen: (ResolvedDesign, A) =
    building = true
    try Negotiator.build(this)
    finally building = false

object Design:
  def apply[A: Dangles](
    moduleName: String
  )(body:       WrapperScope ?=> A
  )(
    using sourcecode.File,
    sourcecode.Line
  ): Design[A] =
    new Design(moduleName, body, here)

final class DesignInstance[A] private[syntheke] (val ports: A, private[syntheke] val boundaries: BoundaryImport)

extension (instance: DesignInstance[?]) def probes: ProbeCatalog = instance.boundaries.probes

extension [A](design: Design[A])
  /** The boundaries the design hands out, to read its frozen result by. */
  def ports: A = design.frozen._2

  def probes: ProbeCatalog = design.frozen._1.probes

  def edgeOf[P <: Protocol](
    boundary: Boundary[P]
  )(
    using sourcecode.File,
    sourcecode.Line
  ): boundary.protocol.Edge =
    design.frozen._1.boundaryEdge(boundary, here).edgeAs(boundary.protocol)

  def instantiate(
    using scope: WrapperScope,
    name:        sourcecode.Name,
    file:        sourcecode.File,
    line:        sourcecode.Line
  ): DesignInstance[A] = scope.instantiate(design, _ => Seq.empty, name.value, here)

  /** An instance each boundary of which `feeds` pairs with domains of this design, each taking the place of the frozen
    * domain of its kind there; every other frozen domain the boundaries reach is copied under the instance.
    */
  def instantiate(
    feeds:       A => Seq[(Boundary[?], Seq[Domain[?]])]
  )(
    using scope: WrapperScope,
    name:        sourcecode.Name,
    file:        sourcecode.File,
    line:        sourcecode.Line
  ): DesignInstance[A] = scope.instantiate(design, feeds, name.value, here)

/** Names what `body` declares where no `val` names it: a node, a domain or an instance made in a loop, or a node whose
  * draft is held under another name.
  */
def named[A](name: String)(body: sourcecode.Name ?=> A): A = body(
  using sourcecode.Name(name)
)

def wrapper[A](
  moduleName: String
)(body:       WrapperScope ?=> A
)(
  using ws:   WrapperScope,
  name:       sourcecode.Name,
  file:       sourcecode.File,
  line:       sourcecode.Line
): A = ws.wrapper(name.value, moduleName, here)(body)

def generator[FP]: GeneratorCall[FP] = new GeneratorCall[FP]

final class GeneratorCall[FP] private[syntheke] ():
  def apply[A](
    body:             GeneratorScope[FP] ?=> A
  )(
    using definition: GeneratorDefinition[FP],
    ws:               WrapperScope,
    name:             sourcecode.Name,
    file:             sourcecode.File,
    line:             sourcecode.Line
  ): A = ws.generator(name.value, definition, here)(body)

extension [K <: DomainKind](kind: K)
  /** A domain of this kind, named after its `val`. */
  def declare(
    definition:    kind.Definition
  )(
    using context: BuildContext[?],
    name:          sourcecode.Name,
    file:          sourcecode.File,
    line:          sourcecode.Line
  ): Domain[K] = context.declareDomain(kind, name.value, definition, here)

def probe[FP, P](
  selector: ProbeSelector[FP, P]
)(
  using gs: GeneratorScope[FP],
  name:     sourcecode.Name,
  file:     sourcecode.File,
  line:     sourcecode.Line
): ProbeNode[P] = gs.declareProbe(selector, name.value, here)

def inward[FP](
  p:        Protocol
)(domains:  Domain[?]*
)(
  using gs: GeneratorScope[FP],
  name:     sourcecode.Name,
  file:     sourcecode.File,
  line:     sourcecode.Line
): p.InwardDraft = gs.inward(p, domains, name.value, here)

def outward[FP](
  p:        Protocol
)(domains:  Domain[?]*
)(
  using gs: GeneratorScope[FP],
  name:     sourcecode.Name,
  file:     sourcecode.File,
  line:     sourcecode.Line
): p.OutwardDraft = gs.outward(p, domains, name.value, here)

def parameters[FP](
  compute:  EdgeView => Either[Violation, FP]
)(
  using gs: GeneratorScope[FP],
  file:     sourcecode.File,
  line:     sourcecode.Line
): Unit = gs.parameters(compute, here)

extension [P <: Protocol](target: InwardPort[P])
  infix def <--(
    source:   OutwardPort[P]
  )(
    using ws: WrapperScope,
    file:     sourcecode.File,
    line:     sourcecode.Line
  ): Unit =
    ws.recordBind(source, target, here)

  infix def <--(
    source:   OutwardBoundary[P]
  )(
    using ws: WrapperScope,
    file:     sourcecode.File,
    line:     sourcecode.Line
  ): Unit = ws.connectBoundary(source, target, here)

  def boundary(
    externalParams:  target.protocol.Down
  )(externalDomains: Domain[?]*
  )(
    using ws:        WrapperScope,
    name:            sourcecode.Name,
    file:            sourcecode.File,
    line:            sourcecode.Line
  ): InwardBoundary[P] =
    ws.boundary(target, externalParams, externalDomains, name.value, here).asInstanceOf[InwardBoundary[P]]

extension [P <: Protocol](source: OutwardPort[P])
  def boundary(
    externalParams:  source.protocol.Up
  )(externalDomains: Domain[?]*
  )(
    using ws:        WrapperScope,
    name:            sourcecode.Name,
    file:            sourcecode.File,
    line:            sourcecode.Line
  ): OutwardBoundary[P] =
    ws.boundary(source, externalParams, externalDomains, name.value, here).asInstanceOf[OutwardBoundary[P]]

extension [P <: Protocol](node: InwardNodeDraft[P])
  inline def derive[S](
    sources:    S
  )(compute:    ParameterInputs.Values[S] => Either[Violation, node.protocol.Up]
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): InwardPort[P] =
    ParameterInputs.validate[S](false)
    node.scope.seal(node, ParameterInputs.derived(sources, compute), (file, line))

  def fixed(
    value:      node.protocol.Up
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): InwardPort[P] =
    node.scope.seal(node, NodeComputation.constant(value), here)

extension [P <: Protocol](node: OutwardNodeDraft[P])
  inline def derive[S](
    sources:    S
  )(compute:    ParameterInputs.Values[S] => Either[Violation, node.protocol.Down]
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): OutwardPort[P] =
    ParameterInputs.validate[S](true)
    node.scope.seal(node, ParameterInputs.derived(sources, compute), (file, line))

  def fixed(
    value:      node.protocol.Down
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): OutwardPort[P] =
    node.scope.seal(node, NodeComputation.constant(value), here)

extension (view: EdgeView) def edgeOf(node: Port[?]): node.protocol.Edge = view.lookupEdge(node)

extension (catalog:      ProbeCatalog)
  /** The resolved probes of `contract`. */
  def query[P](contract: ProbeContract[P]): Vector[ResolvedProbe[P]] = catalog.matching(contract)

extension [P <: Protocol](target: InwardBoundary[P])
  def boundary(
    using ws: WrapperScope,
    name:     sourcecode.Name,
    file:     sourcecode.File,
    line:     sourcecode.Line
  ): InwardBoundary[P] = ws.forwardBoundary(target, name.value, here).asInstanceOf[InwardBoundary[P]]

  infix def <--(
    source:   OutwardPort[P]
  )(
    using ws: WrapperScope,
    file:     sourcecode.File,
    line:     sourcecode.Line
  ): Unit =
    ws.connectBoundary(target, source, here)

  infix def <--(
    source:   OutwardBoundary[P]
  )(
    using ws: WrapperScope,
    file:     sourcecode.File,
    line:     sourcecode.Line
  ): Unit = ws.connectBoundaries(source, target, here)

extension [P <: Protocol](source: OutwardBoundary[P])
  def boundary(
    using ws: WrapperScope,
    name:     sourcecode.Name,
    file:     sourcecode.File,
    line:     sourcecode.Line
  ): OutwardBoundary[P] = ws.forwardBoundary(source, name.value, here).asInstanceOf[OutwardBoundary[P]]

extension [P](probe: ResolvedProbe[P])
  def boundary(
    using ws: WrapperScope,
    file:     sourcecode.File,
    line:     sourcecode.Line
  ): ResolvedProbe[P] =
    ws.forwardProbe(probe, here)

extension [P <: Protocol](boundary: Boundary[P])
  /** The domain an instantiated design's boundary is in, as a domain of the instantiating design. */
  def domain[K <: DomainKind](
    kind:        K
  )(
    using scope: BuildContext[?],
    file:        sourcecode.File,
    line:        sourcecode.Line
  ): Domain[K] = scope.boundaryDomain(boundary, kind, here)
