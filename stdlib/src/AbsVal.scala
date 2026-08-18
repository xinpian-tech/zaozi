// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

case class AbsValParameter(width: Int) extends Parameter:
  require(width > 0, "width must be positive")

given upickle.default.ReadWriter[AbsValParameter] = upickle.default.macroRW

class AbsValLayers(parameter: AbsValParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class AbsValIO(parameter: AbsValParameter) extends HWBundle(parameter):
  val A      = Flipped(Bits(parameter.width))
  val ABSVAL = Aligned(Bits(parameter.width))

/** The DV observation surface: the input and the result, exposed for the UT contract. A combinational DUT has no
  * white-box state, so the probe simply mirrors its IO.
  */
class AbsValProbe(parameter: AbsValParameter) extends DVBundle[AbsValParameter, AbsValLayers](parameter):
  val A      = ProbeRead(Bits(parameter.width), layers("Verification"))
  val ABSVAL = ProbeRead(Bits(parameter.width), layers("Verification"))

/** Two's-complement absolute value, `ABSVAL = |A|`.
  *
  * `A` is interpreted as a width-bit signed value while the interface remains `Bits`. A clear sign bit passes `A`
  * through; a set sign bit selects `-A`, computed as `(~A) + 1` through the handwritten incrementer instead of a
  * generic subtractor.
  */
@generator
object AbsVal extends Generator[AbsValParameter, AbsValLayers, AbsValIO, AbsValProbe]:
  override def moduleName(p: AbsValParameter): String = s"AbsVal_width${p.width}"

  def architecture(parameter: AbsValParameter) =
    val io   = summon[Interface[AbsValIO]]
    val sign = io.A.bit(parameter.width - 1)
    val neg  = Incrementer.instantiate(BKAIncrementerParameter(parameter.width))
    neg.io.A := ~io.A

    val absVal        = sign ? (neg.io.SUM, io.A)
    val checkedAbsVal = Contract(absVal) { value =>
      val negExpected = (0.U(parameter.width) - io.A.asUInt).asBits.bits(parameter.width - 1, 0)
      val expected    = sign ? (negExpected, io.A)
      Ensure((value === expected).I, "absval_matches_abs")
    }

    val probe = summon[ProbeInterface[AbsValProbe]]
    layer("Verification"):
      val negExpected = (0.U(parameter.width) - io.A.asUInt).asBits.bits(parameter.width - 1, 0)
      val expected    = sign ? (negExpected, io.A)
      Assert((absVal === expected).I, "absval_matches_abs")

      val aP      = Wire(Bits(parameter.width)); aP      := io.A; probe.A <== aP
      val absvalP = Wire(Bits(parameter.width)); absvalP := absVal; probe.ABSVAL <== absvalP

    io.ABSVAL := checkedAbsVal
