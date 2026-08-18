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

/** The unit-test module for [[AbsVal]].
  *
  * It reuses the DUT's own type parameters — same Parameter, Layers, IO and Probe — so the UT simply *is* an
  * AbsVal-shaped module that wraps the plain DUT: it passes the DUT interface straight through, forwards the DUT's
  * observation Probe, and adds the stimulus `constraints`. The verification concern lives here (`extends HasUT`), which
  * keeps `AbsVal` a reusable DUT with no UT coupling.
  */
@generator
object AbsValUT
    extends Generator[AbsValParameter, AbsValLayers, AbsValIO, AbsValProbe]
    with HasUT[AbsValParameter, AbsValIO]:
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

  def constraints(
    parameter: AbsValParameter
  )(
    using Arena,
    Context,
    Block,
    ConstraintInterface[AbsValIO]
  ): Unit =
    val io = summon[ConstraintInterface[AbsValIO]]
    require(io.A.cycles >= 3, "AbsVal UT requires cycles for positive, zero, and negative inputs")
    smtAssert(io.A.at(0) > 0.S)
    smtAssert(io.A.at(1) === 0.S)
    smtAssert(io.A.at(2) < 0.S)
