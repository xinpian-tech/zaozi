// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.UT
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** The observation surface of the UT: the DUT's `(A, ABSVAL)` forwarded for the frontend to sample. */
class AbsValUTProbe(parameter: AbsValParameter) extends DVBundle[AbsValParameter, AbsValLayers](parameter):
  val A      = ProbeRead(Bits(parameter.width), layers("Verification"))
  val ABSVAL = ProbeRead(Bits(parameter.width), layers("Verification"))

/** The unit-test module for [[AbsVal]].
  *
  * It wraps the plain DUT — passing its interface through and forwarding its observation Probe — and adds the
  * verification intent (`extends UT`) as SVA `Assert`s: the result must be correct in each sign region of `A` (`> 0`,
  * `== 0`, `< 0`). These are properties to *check*, not constraints on the input, so they are `Assert`s (an `Assume`
  * would instead tell the tool to only consider a subset of inputs).
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

      // The verification intent: |A| is correct across the three sign regions of A.
      val a           = io.A.asSInt
      val zero        = 0.S(parameter.width)
      val negExpected = (0.U(parameter.width) - io.A.asUInt).asBits.bits(parameter.width - 1, 0)
      // Each region is an implication, written as `!region | correct` so the assertion stays a plain boolean
      // (a temporal `implies` would be a concurrent property needing a clock this combinational module lacks).
      Assert((!(a > zero) | (instance.io.ABSVAL === io.A)).I, "abs_positive")
      Assert((!(a === zero) | (instance.io.ABSVAL === io.A)).I, "abs_zero")
      Assert((!(a < zero) | (instance.io.ABSVAL === negExpected)).I, "abs_negative")
