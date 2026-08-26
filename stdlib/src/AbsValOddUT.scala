// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Sem, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** The formal-CRV example: [[AbsVal]] under a generation constraint.
  *
  * The verification intent here is a *constraint* C = "A is odd". For witness generation the module asserts ¬C, so a
  * circt-bmc violation trace IS a transaction satisfying C — `FormalUT.generate`'s dual reading. The assert lives in
  * the module body (not a layer): layers lower to `bind`, which would move the assert out of the module circt-bmc
  * solves.
  */
@generator
object AbsValOddUT
    extends Generator[AbsValParameter, AbsValLayers, AbsValIO, AbsValUTProbe]
    with UT[AbsValParameter, AbsValIO]:
  override def moduleName(p: AbsValParameter): String = s"AbsValOddUT_width${p.width}"

  def architecture(parameter: AbsValParameter) =
    val io       = summon[Interface[AbsValIO]]
    val instance = AbsVal.instantiate(parameter)
    // Pass the DUT's interface straight through.
    instance.io.A := io.A
    io.ABSVAL     := instance.io.ABSVAL

    // ¬C — assert A is even; the BMC violation witness is an odd A.
    Assert((!io.A.bit(0)).I, "gen_a_odd")

    val probe = summon[ProbeInterface[AbsValUTProbe]]
    layer("Verification"):
      // Re-expose the DUT's probe as this module's observation contract.
      val aW      = Wire(Bits(parameter.width))
      aW <== instance.probe.A
      probe.A <== aW
      val absvalW = Wire(Bits(parameter.width))
      absvalW <== instance.probe.ABSVAL
      probe.ABSVAL <== absvalW
