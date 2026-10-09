package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

/** The always-on control of a domain's retention flops (see `RetentionBundle`). Both wires come from the always-on
  * side, so the protocol carries the always-on supply and reset.
  */
object Retention extends Protocol:
  type Down = Unit
  type Up   = Unit
  type Edge = Unit

  val carries: Set[DomainKind] = Set(PowerDomain, ResetDomain)
  val accepts: Seq[Accept]     = Seq(Accept(ClockDomain)(_ == ClockRelation.Same))

  def negotiate(down: Unit, up: Unit): Either[Violation, Unit] = Right(())

  def interface(edge: Unit): ProtocolInterface.Bundle =
    import ProtocolInterface.*
    Bundle(Vector(Field("sleep", Bool), Field("reset", Reset)))

  val downWriter: upickle.default.Writer[Unit] = summon
  val upWriter:   upickle.default.Writer[Unit] = summon
  val edgeWriter: upickle.default.Writer[Unit] = summon
