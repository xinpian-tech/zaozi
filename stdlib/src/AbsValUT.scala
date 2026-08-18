// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.{ConstraintInterface, HasUT}
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

case class AbsValUTParameter(width: Int) extends Parameter:
  require(width > 0, "width must be positive")

given upickle.default.ReadWriter[AbsValUTParameter] = upickle.default.macroRW

class AbsValUTLayers(parameter: AbsValUTParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** What the UT drives: the DUT's input. Observation is through the Probe, not through IO. */
class AbsValUTIO(parameter: AbsValUTParameter) extends HWBundle(parameter):
  val A = Flipped(Bits(parameter.width))

/** The observation contract: forwarded from the wrapped [[AbsVal]]'s own Probe. */
class AbsValUTProbe(parameter: AbsValUTParameter) extends DVBundle[AbsValUTParameter, AbsValUTLayers](parameter):
  val a      = ProbeRead(Bits(parameter.width), layers("Verification"))
  val absval = ProbeRead(Bits(parameter.width), layers("Verification"))

/** The unit-test module for [[AbsVal]].
  *
  * The verification concern lives here, not in the DUT: this module instantiates the plain
  * `AbsVal`, drives its input, forwards its observation Probe, and declares the stimulus
  * `constraints`. So `AbsVal` stays a reusable DUT with no UT coupling, and `AbsValUT` is the
  * thing the framework harnesses (`extends HasUT`).
  */
@generator
object AbsValUT
    extends Generator[AbsValUTParameter, AbsValUTLayers, AbsValUTIO, AbsValUTProbe]
    with HasUT[AbsValUTParameter, AbsValUTIO]:
  override def moduleName(p: AbsValUTParameter): String = s"AbsValUT_width${p.width}"

  def architecture(parameter: AbsValUTParameter) =
    val io       = summon[Interface[AbsValUTIO]]
    val instance = AbsVal.instantiate(AbsValParameter(parameter.width))
    instance.io.A := io.A

    val probe = summon[ProbeInterface[AbsValUTProbe]]
    layer("Verification"):
      // Read the DUT's probe and re-expose it as this module's observation contract.
      val aW = Wire(Bits(parameter.width))
      aW <== instance.probe(using summon[TypeImpl]).a
      probe.a <== aW
      val absvalW = Wire(Bits(parameter.width))
      absvalW <== instance.probe(using summon[TypeImpl]).absval
      probe.absval <== absvalW

  def constraints(
    parameter: AbsValUTParameter
  )(
    using Arena,
    Context,
    Block,
    ConstraintInterface[AbsValUTIO]
  ): Unit =
    val io = summon[ConstraintInterface[AbsValUTIO]]
    require(io.A.cycles >= 3, "AbsVal UT requires cycles for positive, zero, and negative inputs")
    smtAssert(io.A.at(0) > 0.S)
    smtAssert(io.A.at(1) === 0.S)
    smtAssert(io.A.at(2) < 0.S)
