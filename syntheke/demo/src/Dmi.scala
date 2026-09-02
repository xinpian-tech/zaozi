// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import upickle.default.ReadWriter

/** The debug module interface: the request/response bus between the transport ([[Jtag]]'s end of the chain) and the
  * debug module. The transport declares the address and data width its scan register carries; the debug module declares
  * what its register file needs, and negotiation rejects a debug module the transport cannot address.
  */

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
      Field("req", channel("addr" -> Bits(e.abits), "data" -> Bits(e.dataBits), "op" -> Bits(2))),
      Field("resp", Flipped(channel("data" -> Bits(e.dataBits), "op" -> Bits(2))))
    )

  val downRW: upickle.default.ReadWriter[DmiMaster] = summon
  val upRW:   upickle.default.ReadWriter[DmiSlave]  = summon
  val edgeRW: upickle.default.ReadWriter[DmiEdge]   = summon
