// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import com.vowstar.ditdah32.JtagInstruction
import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}

val Dtm = new GeneratorEntry[DtmP]

/** The JTAG debug transport ([[DtmGen]]): the TAP pins downward to whoever drives them, the DMI bus onward to the debug
  * module. `abits` is the transport's own scan-register width — negotiation checks the debug module against it.
  */
final class DtmNodes(
  name:   String,
  idcode: Long,
  abits:  Int
)(
  using GeneratorScope[DtmP])
    extends Nodes:
  val clk  = inward(ClockDomain).uFn(_ => Right(()))
  val jtag =
    outward(Jtag).dFn(_ => Right(JtagTap(idcode, DtmNodes.irLength, abits, 32, JtagInstruction.DMI)))
  val dmi  = outward(Dmi).dFn(_ => Right(DmiMaster(name, abits, 32)))
  parameters { view =>
    val e = view.edgeOf(dmi)
    Right(DtmP(idcode, DtmNodes.irLength, e.abits, e.dataBits))
  }

object DtmNodes:
  /** The TAP's instruction register width, fixed by the transport's hardware. */
  val irLength: Int = 5

def debugTransport(
  idcode: Long,
  abits:  Int
)(
  using
  ws:     WrapperScope,
  name:   sourcecode.Name,
  file:   sourcecode.File,
  line:   sourcecode.Line
): DtmNodes =
  generator(Dtm)(new DtmNodes(name.value, idcode, abits))
