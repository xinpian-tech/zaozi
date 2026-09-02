// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}

val Dm = new GeneratorEntry[DmP]

/** The debug module ([[DmGen]]): a DMI slave with one outward port per hart, and an AXI master of its own. Every hart
  * it holds is one negotiated edge, so the hart count is the topology's, not a parameter to keep in sync; the master
  * port is the system bus access the debug spec gives a debugger, and it settles on the fabric like any other master.
  */
final class DmNodes(
  name:        String,
  harts:       Int,
  haltOnReset: Boolean,
  sbIdBits:    Int
)(
  using GeneratorScope[DmP])
    extends Nodes:
  val clk               = inward(ClockDomain).uFn(_ => Right(()))
  val dmi               = inward(Dmi).uFn(_ => Right(DmiSlave(name, DmNodes.addrBits, 32)))
  private val hartPorts = (0 until harts).map { i =>
    given sourcecode.Name = sourcecode.Name(s"hart$i")
    outward(DebugInterrupt).dFn(_ => Right(DebugRequest(i)))
  }

  /** Hart ports are indexed by the hart id the module assigns them. */
  def hart(i: Int): DebugInterrupt.Outward =
    require(i >= 0 && i < harts, s"debug module '$name' has no hart $i (holds $harts)")
    hartPorts(i)

  /** The system bus: one word in flight, so a debugger's download rides the fabric the harts use. */
  val sb =
    outward(Axi4).dFn(_ =>
      Right(AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << sbIdBits), maxFlight = Some(1)))))
    )

  parameters { view =>
    val e     = view.edgeOf(dmi)
    val s     = shapeOf(view, sb)
    val xlens = hartPorts.map(view.edgeOf(_).xlen).distinct
    if xlens.sizeIs != 1 then Left(Violation(s"harts disagree on register width: ${xlens.mkString(", ")}"))
    else if xlens.head != e.dataBits then
      Left(Violation(s"hart register width ${xlens.head} does not match the ${e.dataBits}-bit abstract data path"))
    else Right(DmP(harts, e.abits, e.dataBits, xlens.head, haltOnReset, s.addrBits, s.dataBits, s.idBits))
  }

object DmNodes:
  /** The debug register file's address width (`haltsum0` at 0x40 is the highest register it answers). */
  val addrBits: Int = 7

def debugModule(
  harts:       Int,
  haltOnReset: Boolean,
  sbIdBits:    Int
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): DmNodes =
  generator(Dm)(new DmNodes(name.value, harts, haltOnReset, sbIdBits))
