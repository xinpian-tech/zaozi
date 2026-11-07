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

/** Baugh-Wooley generation and fixed CHN compression remain separate stages. The compressor resolves product[1:0]
  * directly and returns only the remaining `n + m - 2` carry-save columns for the final Brent-Kung adder.
  */
private[default] def csaMultiply(
  parameter: MultiplierParameter,
  a:         Referable[Bits],
  b:         Referable[Bits],
  signed:    Referable[Bool]
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext
): Referable[Bits] =
  val n = parameter.aWidth
  val m = parameter.bWidth

  val aBits = Vector.tabulate(n)(a.bit)
  val bBits = Vector.tabulate(m)(b.bit)
  val aNot  = aBits.map(!_)
  val bNot  = bBits.map(!_)

  val partialProducts   = BaughWooleyGenerator.generatePartialProduct(aNot, bNot, signed)
  val corrections       = BaughWooleyGenerator.generateCorrections(aBits, bBits, aNot, bNot, signed)
  val compressed        = CsaCompressorTree.compress(partialProducts, corrections)
  val (upperProduct, _) = brentKungAdd(compressed.row0, compressed.row1)

  val productBits = Vector(compressed.product0, compressed.product1) ++ upperProduct
  productBits.reverse.map(_.asBits).reduce(_ ## _)
