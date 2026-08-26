// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Marks a Zaozi generator as a unit-test module.
  *
  * The framework derives the DPI contract and the flat lib model from the module's `(IO, Probe)`. The verification
  * intent — SVA assertions and assumptions — lives in the module's own architecture under the verification layer, not
  * in a separate method.
  */
trait UT[PARAM <: Parameter, I <: HWInterface[PARAM]]

/** The probe re-export ceremony, once: inside the Verification layer, each probe point is a wire fed by the observed
  * value and defined into the probe — [[expose]] is that dance as one call per signal.
  */
object Probes:
  def expose[D <: Data & CanProbe & Element, P <: RWProbe[D] | RProbe[D]](
    dst: Referable[P],
    tpe: D,
    src: Referable[D]
  )(
    using Connect[D]
  )(
    using Arena,
    Context,
    Block,
    LayerTree,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    val w = Wire(tpe)
    w := src
    dst <== w
