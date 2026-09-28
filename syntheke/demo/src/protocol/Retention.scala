package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

object Retention extends Protocol:
  type Down = Unit
  type Up = Unit
  type Edge = Unit

  val carries: Set[Domain] = Set(PowerDomain, ResetDomain)

  def negotiate(down: Unit, up: Unit, domains: EdgeDomains): Either[Violation, (Unit, Vector[Constraint])] =
    val out = domains.outward(ClockDomain)
    val in = domains.inward(ClockDomain)
    Right(((), Vector(Seq(out, in).check { view =>
      if view.sameIdentity(out, in) then Right(())
      else Left(Violation("retention control and CPU must use the same clock domain"))
    })))

  def interface(edge: Unit): ProtocolInterface.Bundle =
    import ProtocolInterface.*
    Bundle(Vector(
      Field("save", Bool),
      Field("restore", Bool),
      Field("reset", Reset),
      Field("saved", Flipped(Bool)),
      Field("restored", Flipped(Bool))
    ))

  val downWriter: upickle.default.Writer[Unit] = summon
  val upWriter: upickle.default.Writer[Unit] = summon
  val edgeWriter: upickle.default.Writer[Unit] = summon
