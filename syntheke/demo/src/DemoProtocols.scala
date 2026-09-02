// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

/** Demo protocols beyond the AXI bus: what a sequential device needs from the graph.
  *
  *   - [[ClockDomain]]: the source publishes its frequency downward; the interface is one clock plus one reset.
  *     Consumers derive rate parameters from the settled frequency in `parameters` and reject an unusable clock there.
  *   - [[Serial]]: the transmitter publishes its baud rate downward to whoever terminates the pins; the interface is
  *     `tx` out, `rx` in.
  */

/** One clock and reset; `Down` is the source's frequency in Hz. */
object ClockDomain extends Protocol:
  type Down = Int
  type Up = Unit
  type Edge = Int
  def negotiate(down: Int, up: Unit): Either[Violation, Int]           = Right(down)
  def interfaceOf(edge: Int):         ProtocolBundle                   =
    ProtocolBundle(
      ProtocolInterface.Field("clock", ProtocolInterface.Clock),
      ProtocolInterface.Field("reset", ProtocolInterface.Reset)
    )
  val downRW:                         upickle.default.ReadWriter[Int]  = summon
  val upRW:                           upickle.default.ReadWriter[Unit] = summon
  val edgeRW:                         upickle.default.ReadWriter[Int]  = summon

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
