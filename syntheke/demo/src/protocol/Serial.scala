package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*


object Serial extends Protocol:
  type Down = Int
  type Up   = Unit
  type Edge = Int

  /** A pad drives the pins on the controller's clock. */
  val carries: Set[DomainKind] = Set(ClockDomain, ResetDomain)
  val accepts: Seq[Accept]     = Seq(Accept(PowerDomain)(PowerDomain.atPin))

  def negotiate(
    down: Int,
    up: Unit
  ): Either[Violation, Int] = Right(down)
  def interface(edge: Int): ProtocolInterface.Bundle =
    ProtocolInterface.Bundle(
      Vector(
        ProtocolInterface.Field("tx", ProtocolInterface.Bool),
        ProtocolInterface.Field("rx", ProtocolInterface.Flipped(ProtocolInterface.Bool))
      )
    )
  val downWriter:                     upickle.default.Writer[Int]  = summon
  val upWriter:                       upickle.default.Writer[Unit] = summon
  val edgeWriter:                     upickle.default.Writer[Int]  = summon
