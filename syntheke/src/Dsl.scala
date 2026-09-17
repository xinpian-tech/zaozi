package me.jiuyang.syntheke

import upickle.default.Writer

object Design:
  def apply(
    body: WrapperScope ?=> (Unit, Vector[Constraint])
  )(
    using
    file: sourcecode.File,
    line: sourcecode.Line
  ): DesignSpec =
    new BuildSession().build("Top", (file, line))(body)

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

def testbench[FP]: TestbenchCall[FP] = new TestbenchCall[FP]

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

def depend[FP](
  from: InwardNodeDraft[?],
  to:   OutwardNodeDraft[?]
)(
  using
  gs:   GeneratorScope[FP],
  file: sourcecode.File,
  line: sourcecode.Line
): (DownReader[from.protocol.Down], UpReader[to.protocol.Up]) = gs.depend(from, to)

def parameters[FP](
  compute:  (EdgeView, DomainView) => Either[Violation, FP]
)(
  using gs: GeneratorScope[FP]
): Unit = gs.parameters(compute)

def observedParameters[FP](
  compute:     (ProbeCatalog, EdgeView, DomainView) => Either[Violation, FP]
)(
  using scope: TestbenchScope[FP]
): Unit = scope.observedParameters(compute)

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
