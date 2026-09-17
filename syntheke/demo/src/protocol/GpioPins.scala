package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

object GpioPins extends Protocol:
  type Down = Int
  type Up   = Unit
  type Edge = Int

  val carries: Set[Domain] = Set.empty

  def negotiate(
    down: Int,
    up: Unit,
    domains: EdgeDomains
  ): Either[Violation, (Int, Vector[Constraint])] =
    val boundaryChecks = Vector(ClockDomain, ResetDomain).map { domain =>
      Seq(domains.outward(domain), domains.inward(domain)).check(_ => Right(()))
    }
    Right((down, boundaryChecks :+ PowerDomain.compatible(domains, allowModel = true)))
  def interface(edge: Int): ProtocolInterface.Bundle =
    ProtocolInterface.Bundle(
      Vector(
        ProtocolInterface.Field("out", ProtocolInterface.Bits(edge)),
        ProtocolInterface.Field("oe", ProtocolInterface.Bits(edge)),
        ProtocolInterface.Field("in", ProtocolInterface.Flipped(ProtocolInterface.Bits(edge)))
      )
    )
  val downWriter:                     upickle.default.Writer[Int]  = summon
  val upWriter:                       upickle.default.Writer[Unit] = summon
  val edgeWriter:                     upickle.default.Writer[Int]  = summon
