// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*

/** The clock and reset a sequential module runs on. The source publishes its frequency in Hz downward; consumers derive
  * their rate parameters from the settled frequency in `parameters` and reject an unusable clock there. A generator has
  * no implicit clock — every one is a node, and takes part in exactly one bind like any other.
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
