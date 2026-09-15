package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import upickle.default.Writer


final case class DebugRequest(hartId: Int) derives Writer:
  require(hartId >= 0, s"hart id $hartId must be non-negative")

final case class DebugHartCap(xlen: Int) derives Writer:
  require(xlen > 0, s"xlen $xlen must be positive")

final case class DebugEdge(hartId: Int, xlen: Int) derives Writer

object DebugInterrupt extends Protocol:
  type Down = DebugRequest
  type Up   = DebugHartCap
  type Edge = DebugEdge

  val carries: Set[Domain] = Set.empty

  def negotiate(
    d: DebugRequest,
    u: DebugHartCap,
    domains: EdgeDomains
  ): Either[Violation, (DebugEdge, Vector[Constraint])] =
    val domainChecks = Vector(ClockDomain, ResetDomain).map { domain =>
      val out = domains.outward(domain)
      val in  = domains.inward(domain)
      Seq(out, in).check { view =>
        if view.sameIdentity(out, in) then Right(())
        else Left(Violation(s"${domain.key.show} differs between ${out.key.node.show} and ${in.key.node.show}"))
      }
    }
    Right((
      DebugEdge(d.hartId, u.xlen),
      domainChecks :+ PowerDomain.compatible(domains, allowModel = false)
    ))

  def interface(e: DebugEdge): ProtocolInterface.Bundle =
    import ProtocolInterface.*
    Bundle(
      Vector(
        Field("halt", Bool),
        Field("resume", Bool),
        Field("reset", Bool),
        Field("haltOnReset", Bool),
        Field(
          "cmd",
          Bundle(
            Vector(
              Field("valid", Bool),
              Field("kind", Bits(2)),
              Field("write", Bool),
              Field("regno", Bits(16)),
              Field("size", Bits(3)),
              Field("data", Bits(e.xlen)),
              Field("address", Bits(e.xlen))
            )
          )
        ),
        Field(
          "hart",
          Flipped(
            Bundle(
              Vector(
                Field("halted", Bool),
                Field("running", Bool),
                Field("resumeAck", Bool),
                Field("resetAck", Bool),
                Field("cmdDone", Bool),
                Field("cmdError", Bits(3)),
                Field("cmdRdata", Bits(e.xlen))
              )
            )
          )
        )
      )
    )

  val downWriter: upickle.default.Writer[DebugRequest] = summon
  val upWriter:   upickle.default.Writer[DebugHartCap] = summon
  val edgeWriter: upickle.default.Writer[DebugEdge]    = summon
