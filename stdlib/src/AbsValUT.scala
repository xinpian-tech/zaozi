// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.UT
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** The unit-test module for [[AbsVal]].
  *
  * It reuses the DUT's own type parameters — same Parameter, Layers, IO and Probe — so the UT simply *is* an
  * AbsVal-shaped module that wraps the plain DUT: it passes the DUT interface straight through and forwards the DUT's
  * observation Probe. It is marked [[UT]]; the verification intent (SVA assertions) lives in this architecture, keeping
  * `AbsVal` a reusable DUT with no UT coupling.
  */
@generator
object AbsValUT
    extends Generator[AbsValParameter, AbsValLayers, AbsValIO, AbsValProbe]
    with UT[AbsValParameter, AbsValIO]:
  override def moduleName(p: AbsValParameter): String = s"AbsValUT_width${p.width}"

  def architecture(parameter: AbsValParameter) =
    val io       = summon[Interface[AbsValIO]]
    val instance = AbsVal.instantiate(parameter)
    // Pass the DUT's interface straight through.
    instance.io.A := io.A
    io.ABSVAL     := instance.io.ABSVAL

    val probe = summon[ProbeInterface[AbsValProbe]]
    layer("Verification"):
      // Read the DUT's probe and re-expose it as this module's observation contract.
      val aW      = Wire(Bits(parameter.width))
      aW <== instance.probe.A
      probe.A <== aW
      val absvalW = Wire(Bits(parameter.width))
      absvalW <== instance.probe.ABSVAL
      probe.ABSVAL <== absvalW
