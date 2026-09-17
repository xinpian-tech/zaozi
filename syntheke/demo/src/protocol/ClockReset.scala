package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

object ClockReset extends Protocol:
  type Down = Unit
  type Up   = Unit
  type Edge = Unit

  val carries: Set[Domain] = Set(ClockDomain, ResetDomain)

  def negotiate(
    down: Unit,
    up: Unit,
    domains: EdgeDomains
  ): Either[Violation, (Unit, Vector[Constraint])] =
    Right(((), Vector(PowerDomain.compatible(domains, allowModel = true))))

  def interface(edge: Unit): ProtocolInterface.Bundle =
    ProtocolInterface.Bundle(
      Vector(
        ProtocolInterface.Field("clock", ProtocolInterface.Clock),
        ProtocolInterface.Field("reset", ProtocolInterface.Reset)
      )
    )

  val downWriter: upickle.default.Writer[Unit] = summon
  val upWriter:   upickle.default.Writer[Unit] = summon
  val edgeWriter: upickle.default.Writer[Unit] = summon
