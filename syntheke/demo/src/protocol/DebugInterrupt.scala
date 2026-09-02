// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import upickle.default.ReadWriter

/** One debug module port per hart: the halt request — the RISC-V debug interrupt — with the resume and reset requests,
  * the abstract command channel, and the hart's status back. The debug module assigns the hart its index downward; the
  * hart publishes its register width upward. The hart count is therefore the topology's, not a parameter to keep in
  * sync: one hart is one edge (see also [[Jtag]] and [[Dmi]]).
  */

/** The hart index the debug module assigns to this port. */
final case class DebugRequest(hartId: Int) derives ReadWriter:
  require(hartId >= 0, s"hart id $hartId must be non-negative")

/** What the hart tells the debug module about itself. */
final case class DebugHartCap(xlen: Int) derives ReadWriter:
  require(xlen > 0, s"xlen $xlen must be positive")

final case class DebugEdge(hartId: Int, xlen: Int) derives ReadWriter

object DebugInterrupt extends Protocol:
  type Down = DebugRequest
  type Up   = DebugHartCap
  type Edge = DebugEdge

  def negotiate(d: DebugRequest, u: DebugHartCap): Either[Violation, DebugEdge] = Right(DebugEdge(d.hartId, u.xlen))

  /** From the debug module's side: the requests and the abstract command out, the hart's status back. */
  def interfaceOf(e: DebugEdge): ProtocolBundle =
    import ProtocolInterface.*
    ProtocolBundle(
      Field("halt", Bool),
      Field("resume", Bool),
      Field("reset", Bool),
      Field("haltOnReset", Bool),
      Field(
        "cmd",
        Bundle(
          Vector(
            Field("valid", Bool),
            Field("kind", Bits(2)),
            Field("write", Bool),
            Field("regno", Bits(16)),
            Field("size", Bits(3)),
            Field("data", Bits(e.xlen)),
            Field("address", Bits(e.xlen))
          )
        )
      ),
      Field(
        "hart",
        Flipped(
          Bundle(
            Vector(
              Field("halted", Bool),
              Field("running", Bool),
              Field("resumeAck", Bool),
              Field("resetAck", Bool),
              Field("cmdDone", Bool),
              Field("cmdError", Bits(3)),
              Field("cmdRdata", Bits(e.xlen))
            )
          )
        )
      )
    )

  val downRW: upickle.default.ReadWriter[DebugRequest] = summon
  val upRW:   upickle.default.ReadWriter[DebugHartCap] = summon
  val edgeRW: upickle.default.ReadWriter[DebugEdge]    = summon
