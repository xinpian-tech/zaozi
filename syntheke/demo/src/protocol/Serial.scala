// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

/** A serial pin pair leaving the chip. The transmitter publishes its baud rate downward to whoever terminates the pins,
  * so the terminal cannot disagree with the UART about the line.
  */

/** A serial pin pair; `Down` is the transmitter's baud rate. */
object Serial extends Protocol:
  type Down = Int
  type Up = Unit
  type Edge = Int
  def negotiate(down: Int, up: Unit): Either[Violation, Int]           = Right(down)
  def interfaceOf(edge: Int):         ProtocolBundle                   =
    ProtocolBundle(
      ProtocolInterface.Field("tx", ProtocolInterface.Bool),
      ProtocolInterface.Field("rx", ProtocolInterface.Flipped(ProtocolInterface.Bool))
    )
  val downRW:                         upickle.default.ReadWriter[Int]  = summon
  val upRW:                           upickle.default.ReadWriter[Unit] = summon
  val edgeRW:                         upickle.default.ReadWriter[Int]  = summon
