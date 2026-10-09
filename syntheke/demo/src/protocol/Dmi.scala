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

  val carries: Set[DomainKind] = Set.empty
  val accepts: Seq[Accept]     = Seq(
    Accept(ClockDomain)(_ == ClockRelation.Same),
    Accept(ResetDomain) {
      case ResetRelation.Same                 => true
      case ResetRelation.Different(sameLevel) => sameLevel
    },
    Accept(PowerDomain)(_ == PowerRelation.Same)
  )

  def negotiate(
    m: DmiMaster,
    s: DmiSlave
  ): Either[Violation, DmiEdge] =
    if s.addrBits > m.abits then
      Left(
        Violation(
          s"debug module addresses ${s.addrBits} register bits but transport scans only ${m.abits}"
        )
      )
    else if s.dataBits != m.dataBits then
      Left(Violation(s"DMI data width mismatch: transport ${m.dataBits}, module ${s.dataBits}"))
    else Right(DmiEdge(m.abits, m.dataBits))

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
