// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import upickle.default.ReadWriter

/** The TAP pins, one of the three boundaries the RISC-V debug chain is cut at — [[Dmi]] and [[DebugInterrupt]] are the
  * other two, and each is where a pair of parameters actually meets. The transport publishes its TAP identity downward
  * (idcode, IR length, DMI address width, the IR value that selects DMI), so whoever drives the pins knows how to scan
  * it without being told twice.
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
