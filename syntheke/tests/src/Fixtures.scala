package me.jiuyang.syntheke

import upickle.default.Writer

/** Domain kinds, protocols and a generator just rich enough to drive negotiation. */
object Fixtures:
  object Clock extends DomainKind:
    type Definition = Int

    val name = "clock"

    def map(hz:          Int)(f: DomainMap):  Int         = hz
    def describe(domain: Domain[Clock.type]): ujson.Value = ujson.Num(domain.definition)

  /** A reset released on the clock its definition names. */
  object Reset extends DomainKind:
    type Definition = Domain[Clock.type]

    val name = "reset"

    def map(clock:       Domain[Clock.type])(f: DomainMap): Domain[Clock.type] = f(clock)
    def describe(domain: Domain[Reset.type]):               ujson.Value        = ujson.Null

  object Power extends DomainKind:
    type Definition = Unit

    val name = "power"

    def map(definition:  Unit)(f: DomainMap): Unit        = ()
    def describe(domain: Domain[Power.type]): ujson.Value = ujson.Null

  /** A data bus whose width travels down; it may cross anything. */
  object Bus extends Protocol:
    type Down = Int
    type Up   = Unit
    type Edge = Int

    val carries: Vector[DomainKind] = Vector.empty
    def accepts: Seq[Accept]        =
      Seq(Accept(Clock)((_, _) => true), Accept(Reset)((_, _) => true), Accept(Power)((_, _) => true))

    def negotiate(down: Int, up: Unit): Either[Violation, Int]   = Right(down)
    def interface(edge: Int):           ProtocolInterface.Bundle =
      ProtocolInterface.Bundle(Vector(ProtocolInterface.Field("data", ProtocolInterface.UInt(edge))))

    val downWriter: Writer[Int]  = summon
    val upWriter:   Writer[Unit] = summon
    val edgeWriter: Writer[Int]  = summon

  /** A reset wire: its inward end is in the reset domain of its outward end. */
  object ResetWire extends Protocol:
    type Down = Unit
    type Up   = Unit
    type Edge = Unit

    val carries: Vector[DomainKind] = Vector(Reset)
    def accepts: Seq[Accept]        = Seq.empty

    def negotiate(down: Unit, up: Unit): Either[Violation, Unit]  = Right(())
    def interface(edge: Unit):           ProtocolInterface.Bundle =
      ProtocolInterface.Bundle(Vector(ProtocolInterface.Field("reset", ProtocolInterface.Reset)))

    val downWriter: Writer[Unit] = summon
    val upWriter:   Writer[Unit] = summon
    val edgeWriter: Writer[Unit] = summon

  /** A protocol whose interface wrongly carries a probe. */
  object Peek extends Protocol:
    type Down = Unit
    type Up   = Unit
    type Edge = Unit

    val carries: Vector[DomainKind] = Vector.empty
    def accepts: Seq[Accept]        = Seq.empty

    def negotiate(down: Unit, up: Unit): Either[Violation, Unit]  = Right(())
    def interface(edge: Unit):           ProtocolInterface.Bundle =
      ProtocolInterface.Bundle(
        Vector(ProtocolInterface.Field("peek", ProtocolInterface.Probe(ProtocolInterface.UInt(1), None)))
      )

    val downWriter: Writer[Unit] = summon
    val upWriter:   Writer[Unit] = summon
    val edgeWriter: Writer[Unit] = summon

  given GeneratorDefinition[Int] = new GeneratorDefinition[Int]("leaf"):
    def probes(fullParam:       Int): ProbeDeclaration = new ProbeDeclaration { def ports = Vector.empty }
    def observations(fullParam: Int): ProbeBindings    = ProbeBindings.empty

  /** A generator module with no nodes. */
  def empty(
    using WrapperScope,
    sourcecode.Name,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    generator[Int](parameters(_ => Right(0)))

  /** A generator module driving a bus of `width`. */
  def source(
    width: Int
  )(
    using WrapperScope,
    sourcecode.Name,
    sourcecode.File,
    sourcecode.Line
  ): Bus.Outward =
    generator[Int] {
      val out = named("out")(outward(Bus)().fixed(width))
      parameters(_ => Right(0))
      out
    }

  /** A generator module taking a bus. */
  def sink(
    using WrapperScope,
    sourcecode.Name,
    sourcecode.File,
    sourcecode.Line
  ): Bus.Inward =
    generator[Int] {
      val in = named("in")(inward(Bus)().fixed(()))
      parameters(_ => Right(0))
      in
    }

  /** The message of the negotiation error `design` raises. */
  def failure(design: => Design[?]): String =
    try
      Negotiator.negotiate(design)
      throw AssertionError("negotiation succeeded")
    catch case e: NegotiationException => e.getMessage
