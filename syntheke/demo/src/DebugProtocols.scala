// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import upickle.default.ReadWriter

/** The debug subsystem's three protocols — the RISC-V debug chain expressed as negotiated edges, one per boundary:
  *
  *   - [[Jtag]]: the TAP pins. The transport publishes its TAP identity downward (idcode, IR length, DMI address width,
  *     the IR value that selects DMI) so whoever drives the pins knows how to scan it.
  *   - [[Dmi]]: the debug module interface, a request/response bus. The transport declares the address and data width
  *     its scan register carries; the debug module declares what its register file needs, and negotiation rejects a
  *     debug module the transport cannot address.
  *   - [[DebugInterrupt]]: one debug module port per hart — the halt request (the RISC-V debug interrupt) with the
  *     resume/reset requests, the abstract command channel and the hart's status back. The debug module assigns the
  *     hart its index; the hart publishes its register width upward.
  */

/** What a TAP publishes to its driver: enough to scan it — the identity register, the instruction that selects DMI, and
  * the DMI scan register's two payload widths.
  */
final case class JtagTap(idcode: Long, irLength: Int, abits: Int, dataBits: Int, dmiInstruction: Int)
    derives ReadWriter:
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
  type Up = Unit
  type Edge = JtagTap
  def negotiate(down: JtagTap, up: Unit): Either[Violation, JtagTap] = Right(down)

  /** From the TAP's side: it is clocked and driven by whoever holds the pins, and answers on `tdo`. */
  def interfaceOf(edge: JtagTap): ProtocolBundle                      =
    import ProtocolInterface.*
    ProtocolBundle(
      Field("tck", Flipped(Clock)),
      Field("tms", Flipped(Bool)),
      Field("tdi", Flipped(Bool)),
      Field("trstN", Flipped(Bool)),
      Field("tdo", Bool)
    )
  val downRW:                     upickle.default.ReadWriter[JtagTap] = summon
  val upRW:                       upickle.default.ReadWriter[Unit]    = summon
  val edgeRW:                     upickle.default.ReadWriter[JtagTap] = summon

/** The transport's scan-register capacity. */
final case class DmiMaster(name: String, abits: Int, dataBits: Int) derives ReadWriter:
  require(abits >= 1, s"$name: DMI address width $abits must be positive")
  require(dataBits > 0, s"$name: DMI data width $dataBits must be positive")

/** What the debug module's register file needs. */
final case class DmiSlave(name: String, addrBits: Int, dataBits: Int) derives ReadWriter:
  require(addrBits >= 1, s"$name: DMI address width $addrBits must be positive")
  require(dataBits > 0, s"$name: DMI data width $dataBits must be positive")

final case class DmiEdge(abits: Int, dataBits: Int) derives ReadWriter

object Dmi extends Protocol:
  type Down = DmiMaster
  type Up   = DmiSlave
  type Edge = DmiEdge

  def negotiate(m: DmiMaster, s: DmiSlave): Either[Violation, DmiEdge] =
    if s.addrBits > m.abits then
      Left(
        Violation(
          s"debug module '${s.name}' addresses ${s.addrBits} register bits but transport '${m.name}' scans only ${m.abits}"
        )
      )
    else if s.dataBits != m.dataBits then
      Left(Violation(s"DMI data width mismatch: transport '${m.name}' ${m.dataBits}, module '${s.name}' ${s.dataBits}"))
    else Right(DmiEdge(m.abits, m.dataBits))

  /** From the transport's side: a request out, a response back, both valid/ready. */
  def interfaceOf(e: DmiEdge): ProtocolBundle =
    import ProtocolInterface.*
    def channel(payload: (String, ProtocolInterface)*): Bundle =
      Bundle(
        Vector(
          Field("valid", Bool),
          Field("ready", Flipped(Bool)),
          Field("bits", Bundle(payload.toVector.map((n, t) => Field(n, t))))
        )
      )
    ProtocolBundle(
      Field("req", channel("addr" -> UInt(e.abits), "data" -> UInt(e.dataBits), "op" -> UInt(2))),
      Field("resp", Flipped(channel("data" -> UInt(e.dataBits), "op" -> UInt(2))))
    )

  val downRW: upickle.default.ReadWriter[DmiMaster] = summon
  val upRW:   upickle.default.ReadWriter[DmiSlave]  = summon
  val edgeRW: upickle.default.ReadWriter[DmiEdge]   = summon

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
            Field("kind", UInt(2)),
            Field("write", Bool),
            Field("regno", UInt(16)),
            Field("size", UInt(3)),
            Field("data", UInt(e.xlen)),
            Field("address", UInt(e.xlen))
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
              Field("cmdError", UInt(3)),
              Field("cmdRdata", UInt(e.xlen))
            )
          )
        )
      )
    )

  val downRW: upickle.default.ReadWriter[DebugRequest] = summon
  val upRW:   upickle.default.ReadWriter[DebugHartCap] = summon
  val edgeRW: upickle.default.ReadWriter[DebugEdge]    = summon
