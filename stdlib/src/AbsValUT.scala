// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.UT
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** The observation surface of the UT: the DUT's `(A, ABSVAL)` forwarded, plus `assumeOk` — the input-constraint
  * predicate. The constraint is written once and used twice: as an SVA `Assume` (formal-usable) and as this probe,
  * which a constrained-random frontend samples against.
  */
class AbsValUTProbe(parameter: AbsValParameter) extends DVBundle[AbsValParameter, AbsValLayers](parameter):
  val A        = ProbeRead(Bits(parameter.width), layers("Verification"))
  val ABSVAL   = ProbeRead(Bits(parameter.width), layers("Verification"))
  val assumeOk = ProbeRead(Bits(1), layers("Verification"))

/** The unit-test module for [[AbsVal]].
  *
  * It wraps the plain DUT — passing its interface through and forwarding its observation Probe — and adds the
  * verification intent (`extends UT`): an input constraint expressed as SVA. Here the constraint is "A is odd", and the
  * frontend generates stimulus that satisfies it.
  */
@generator
object AbsValUT
    extends Generator[AbsValParameter, AbsValLayers, AbsValIO, AbsValUTProbe]
    with UT[AbsValParameter, AbsValIO]:
  override def moduleName(p: AbsValParameter): String = s"AbsValUT_width${p.width}"

  def architecture(parameter: AbsValParameter) =
    val io       = summon[Interface[AbsValIO]]
    val instance = AbsVal.instantiate(parameter)
    // Pass the DUT's interface straight through.
    instance.io.A := io.A
    io.ABSVAL     := instance.io.ABSVAL

    val probe = summon[ProbeInterface[AbsValUTProbe]]
    layer("Verification"):
      // Re-expose the DUT's probe as this module's observation contract.
      val aW      = Wire(Bits(parameter.width))
      aW <== instance.probe.A
      probe.A <== aW
      val absvalW = Wire(Bits(parameter.width))
      absvalW <== instance.probe.ABSVAL
      probe.ABSVAL <== absvalW

      // The input constraint, as SVA — and mirrored to a probe the frontend samples against.
      val odd = io.A.bit(0) // A is odd
      Assume(odd.I)
      val okW = Wire(Bits(1))
      okW := odd.asBits
      probe.assumeOk <== okW
