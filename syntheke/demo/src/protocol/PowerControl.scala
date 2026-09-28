package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

object PowerControl extends Protocol:
  type Down = Unit
  type Up   = Unit
  type Edge = Unit

  val carries: Set[Domain] = Set.empty

  def negotiate(
    down: Unit,
    up: Unit,
    domains: EdgeDomains
  ): Either[Violation, (Unit, Vector[Constraint])] =
    val checks = Vector(ClockDomain, ResetDomain).map { domain =>
      val out = domains.outward(domain)
      val in = domains.inward(domain)
      Seq(out, in).check { view =>
        if view.sameIdentity(out, in) then Right(())
        else Left(Violation(s"${domain.key.show} differs between ${out.key.node.show} and ${in.key.node.show}"))
      }
    }
    Right(((), checks :+ PowerDomain.compatible(domains, allowModel = false)))

  def interface(edge: Unit): ProtocolInterface.Bundle =
    import ProtocolInterface.*
    Bundle(Vector(
      Field("requestOn", Bool),
      Field("on", Flipped(Bool)),
      Field("busy", Flipped(Bool)),
      Field("powerGood", Flipped(Bool)),
      Field("isolated", Flipped(Bool))
    ))

  val downWriter: upickle.default.Writer[Unit] = summon
  val upWriter: upickle.default.Writer[Unit] = summon
  val edgeWriter: upickle.default.Writer[Unit] = summon
