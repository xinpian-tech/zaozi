package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import upickle.default.Writer


final case class DmiMaster(abits: Int, dataBits: Int) derives Writer:
  require(abits >= 1, s"DMI address width $abits must be positive")
  require(dataBits > 0, s"DMI data width $dataBits must be positive")

final case class DmiSlave(addrBits: Int, dataBits: Int) derives Writer:
  require(addrBits >= 1, s"DMI address width $addrBits must be positive")
  require(dataBits > 0, s"DMI data width $dataBits must be positive")

final case class DmiEdge(abits: Int, dataBits: Int) derives Writer

object Dmi extends Protocol:
  type Down = DmiMaster
  type Up   = DmiSlave
  type Edge = DmiEdge

  val carries: Set[Domain] = Set.empty

  def negotiate(
    m: DmiMaster,
    s: DmiSlave,
    domains: EdgeDomains
  ): Either[Violation, (DmiEdge, Vector[Constraint])] =
    if s.addrBits > m.abits then
      Left(
        Violation(
          s"debug module addresses ${s.addrBits} register bits but transport scans only ${m.abits}"
        )
      )
    else if s.dataBits != m.dataBits then
      Left(Violation(s"DMI data width mismatch: transport ${m.dataBits}, module ${s.dataBits}"))
    else
      val outClock = domains.outward(ClockDomain)
      val inClock  = domains.inward(ClockDomain)
      val clockCheck = Seq(outClock, inClock).check { view =>
        if view.sameIdentity(outClock, inClock) then Right(())
        else Left(Violation(s"clock domain differs between ${outClock.key.node.show} and ${inClock.key.node.show}"))
      }
      Right((
        DmiEdge(m.abits, m.dataBits),
        Vector(clockCheck, ResetDomain.compatible(domains), PowerDomain.compatible(domains, allowModel = false))
      ))

  def interface(e: DmiEdge): ProtocolInterface.Bundle =
    import ProtocolInterface.*
    def channel(payload: (String, ProtocolInterface)*): Bundle =
      Bundle(
        Vector(
          Field("valid", Bool),
          Field("ready", Flipped(Bool)),
          Field("bits", Bundle(payload.toVector.map((n, t) => Field(n, t))))
        )
      )
    Bundle(
      Vector(
        Field("req", channel("addr" -> Bits(e.abits), "data" -> Bits(e.dataBits), "op" -> Bits(2))),
        Field("resp", Flipped(channel("data" -> Bits(e.dataBits), "op" -> Bits(2))))
      )
    )

  val downWriter: upickle.default.Writer[DmiMaster] = summon
  val upWriter:   upickle.default.Writer[DmiSlave]  = summon
  val edgeWriter: upickle.default.Writer[DmiEdge]   = summon
