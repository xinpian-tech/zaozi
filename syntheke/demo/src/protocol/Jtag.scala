package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import upickle.default.Writer


final case class JtagTap(idcode: Long, irLength: Int, abits: Int, dataBits: Int, dmiInstruction: Int)
    derives Writer:
  require(idcode >= 0L && idcode <= 0xffffffffL, s"idcode 0x${idcode.toHexString} must fit in 32 bits")
  require((idcode & 1L) == 1L, "idcode bit 0 must be one (JTAG requires it)")
  require(irLength >= 2, s"IR length $irLength must be at least 2")
  require(abits >= 1, s"DMI address width $abits must be positive")
  require(dataBits > 0, s"DMI data width $dataBits must be positive")
  require(
    dmiInstruction >= 0 && dmiInstruction < (1 << irLength),
    s"DMI instruction 0x${dmiInstruction.toHexString} does not fit in $irLength IR bits"
  )

object Jtag extends Protocol:
  type Down = JtagTap
  type Up   = Unit
  type Edge = JtagTap

  val carries: Set[Domain] = Set.empty

  def negotiate(
    down: JtagTap,
    up: Unit,
    domains: EdgeDomains
  ): Either[Violation, (JtagTap, Vector[Constraint])] =
    val outClock = domains.outward(ClockDomain)
    val inClock  = domains.inward(ClockDomain)
    val clockCheck = Seq(outClock, inClock).check { view =>
      if view.sameIdentity(outClock, inClock) then Right(())
      else Left(Violation(s"clock domain differs between ${outClock.key.node.show} and ${inClock.key.node.show}"))
    }
    val resetCheck = Seq(domains.outward(ResetDomain), domains.inward(ResetDomain)).check(_ => Right(()))
    Right((down, Vector(clockCheck, resetCheck, PowerDomain.compatible(domains, allowModel = true))))

  def interface(edge: JtagTap): ProtocolInterface.Bundle =
    import ProtocolInterface.*
    Bundle(
      Vector(
        Field("tms", Flipped(Bool)),
        Field("tdi", Flipped(Bool)),
        Field("trstN", Flipped(Bool)),
        Field("tdo", Bool)
      )
    )
  val downWriter:                 upickle.default.Writer[JtagTap] = summon
  val upWriter:                   upickle.default.Writer[Unit]    = summon
  val edgeWriter:                 upickle.default.Writer[JtagTap] = summon
