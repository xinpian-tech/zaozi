package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

object IO extends Protocol:
  type Down = Unit
  type Up = Unit
  type Edge = Unit

  val carries: Set[DomainKind] = Set.empty
  val accepts: Seq[Accept]     = Accepts.pin

  def negotiate(down: Unit, up: Unit): Either[Violation, Unit] = Right(())

  def interface(edge: Unit): ProtocolInterface.Bundle =
    import ProtocolInterface.*
    Bundle(Vector(
      Field("inputEnable", Bool),
      Field("outputValue", Bool),
      Field("outputEnable", Bool),
      Field("inputValue", Flipped(Bool))
    ))

  val downWriter: upickle.default.Writer[Unit] = summon
  val upWriter: upickle.default.Writer[Unit] = summon
  val edgeWriter: upickle.default.Writer[Unit] = summon
