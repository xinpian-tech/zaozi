// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.multiplier.default

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import me.jiuyang.stdlib.multiplier.{*, given}

/** The public `n x m` integer multiplier: dispatches -- purely by parameter -- to the cheapest combinational
  * specialization for the operand widths and carries a `Contract` proving `product == a * b`.
  *
  * Because the dispatch is compile-time, each `(aWidth, bWidth)` elaborates exactly one specialization directly into
  * this module (no runtime mux and no leaf multiplier module). The `Contract` verifies the selected implementation at
  * the exact configured width. The routing mirrors the reference, including the intentional asymmetry that
  * `aWidth == 3` is specialized while an `n x 3` is not.
  */
@generator
object Multiplier extends Generator[MultiplierParameter, MultiplierLayers, MultiplierIO, MultiplierProbe]:
  override def moduleName(p: MultiplierParameter): String =
    s"Multiplier_aWidth${p.aWidth}_bWidth${p.bWidth}"

  def architecture(parameter: MultiplierParameter) =
    val io = summon[Interface[MultiplierIO]]

    val productWord = (parameter.aWidth, parameter.bWidth) match
      case (1, _) | (_, 1) => oneByVectorMultiply(parameter, io.a, io.b, io.signed)
      case (2, _) | (_, 2) => twoByVectorMultiply(parameter, io.a, io.b, io.signed)
      case (3, _)          => threeByVectorMultiply(parameter, io.a, io.b, io.signed)
      case _               => csaMultiply(parameter, io.a, io.b, io.signed)

    val checkedProduct = Contract(productWord) { product =>
      val unsignedExpected = (io.a.asUInt * io.b.asUInt).asBits.bits(parameter.productWidth - 1, 0)
      val signedExpected   = (io.a.asSInt * io.b.asSInt).asBits.bits(parameter.productWidth - 1, 0)
      val expected         = io.signed ? (signedExpected, unsignedExpected)
      Ensure((product === expected).I, Some("multiplier_matches_mul"))
    }

    io.product := checkedProduct
