// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

/** A GPIO pin bank; `Down` is the bank width. */
object GpioPins extends Protocol:
  type Down = Int
  type Up = Unit
  type Edge = Int
  def negotiate(down: Int, up: Unit): Either[Violation, Int]           = Right(down)
  def interfaceOf(edge: Int):         ProtocolBundle                   =
    ProtocolBundle(
      ProtocolInterface.Field("out", ProtocolInterface.Bits(edge)),
      ProtocolInterface.Field("oe", ProtocolInterface.Bits(edge)),
      ProtocolInterface.Field("in", ProtocolInterface.Flipped(ProtocolInterface.Bits(edge)))
    )
  val downRW:                         upickle.default.ReadWriter[Int]  = summon
  val upRW:                           upickle.default.ReadWriter[Unit] = summon
  val edgeRW:                         upickle.default.ReadWriter[Int]  = summon
