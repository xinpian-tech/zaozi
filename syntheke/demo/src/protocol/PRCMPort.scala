package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

/** One port of zaozi PR #159's PRCM, field for field its `PRCMDomainPort`: the PRCM drives the managed domain's clock
  * and reset and requests power, isolation and quiesce; the domain answers on the PRCM's management clock.
  */
object PRCMPort extends Protocol:
  type Down = Unit
  type Up   = Unit
  type Edge = Unit

  /** The managed domain, its clock and the reset the PRCM releases on that clock. */
  val carries: Set[DomainKind] = Set(PRCMDomain, ClockDomain, ResetDomain)
  val accepts: Seq[Accept]     = Seq(Accept(PowerDomain)(_ == PowerRelation.Same))

  def negotiate(down: Unit, up: Unit): Either[Violation, Unit] = Right(())

  def interface(edge: Unit): ProtocolInterface.Bundle =
    import ProtocolInterface.*
    Bundle(
      Vector(
        Field("powerGood", Flipped(Bool)),
        Field("isolationActive", Flipped(Bool)),
        Field("idle", Flipped(Bool)),
        Field("powerRequest", Bool),
        Field("isolationRequest", Bool),
        Field("quiesceRequest", Bool),
        Field("clock", Clock),
        Field("resetN", UInt(1))
      )
    )

  val downWriter: upickle.default.Writer[Unit] = summon
  val upWriter:   upickle.default.Writer[Unit] = summon
  val edgeWriter: upickle.default.Writer[Unit] = summon
