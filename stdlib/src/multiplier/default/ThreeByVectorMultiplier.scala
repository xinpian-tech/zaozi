// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.multiplier.default

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import me.jiuyang.stdlib.{AbsVal, AbsValParameter}
import me.jiuyang.stdlib.multiplier.{*, given}

/** Width-specialized multiplication for when `A` is exactly three bits and `B` is at least three, selected purely from
  * the parameter. Signed or unsigned is chosen at runtime by `signed`.
  *
  * The asymmetry is intentional and matches the reference: only `aWidth == 3` is specialized here; an `n x 3` falls
  * through to the general multiplier. Three partial-product rows are compressed 3:2 per column, then the carry row and
  * shifted sums are summed by a Brent-Kung adder. This yields the magnitude product `|A| * |B|` (signed) or the plain
  * product (unsigned), which [[me.jiuyang.stdlib.multiplier.addSign]] then signs. `Multiplier` proves the returned
  * result with its top-level `Contract`.
  */
private[default] def threeByVectorMultiply(
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
  require(
    parameter.aWidth == 3 && parameter.bWidth >= 3,
    "threeByVectorMultiply requires aWidth == 3 and bWidth >= 3"
  )

  val n     = parameter.aWidth
  val m     = parameter.bWidth
  val zero  = false.B
  val aBits = Vector.tabulate(n)(a.bit)
  val bBits = Vector.tabulate(m)(b.bit)

  // 3-bit operand times a vector: three partial-product rows reduced 3:2 per
  // column, then the carry row plus shifted sums added by the Brent-Kung adder.
  def threeByVector(
    threeBits:       Vector[Referable[Bool]],
    threeMagnitude:  Vector[Referable[Bool]],
    vector:          Vector[Referable[Bool]],
    vectorMagnitude: Vector[Referable[Bool]]
  ): Vector[Referable[Bool]] =
    val threeCoefficients  = threeBits.zip(threeMagnitude).map { (bit, magnitudeBit) => signed ? (magnitudeBit, bit) }
    val vectorCoefficients = vector.zip(vectorMagnitude).map { (bit, magnitudeBit) => signed ? (magnitudeBit, bit) }
    val row0               = vectorCoefficients.map(threeCoefficients(0) & _)
    val row1               = vectorCoefficients.map(threeCoefficients(1) & _)
    val row2               = vectorCoefficients.map(threeCoefficients(2) & _)

    val compressed = Vector.tabulate(vector.size + 2) { column =>
      val inA = if column < vector.size then row0(column) else zero
      val inB = if column == 0 || column > vector.size then zero else row1(column - 1)
      val inC = if column < 2 then zero else row2(column - 2)
      compressAdd32(inA, inB, inC)
    }

    val carryRow    = compressed.map(_.carry)
    val shiftedSums = compressed.tail.map(_.sum) :+ zero
    val (sum, _)    = brentKungAdd(carryRow, shiftedSums)
    compressed.head.sum +: sum

  val aAbs = AbsVal.instantiate(AbsValParameter(n))
  aAbs.io.A := a
  val bAbs = AbsVal.instantiate(AbsValParameter(m))
  bAbs.io.A := b

  val magnitudeProduct = threeByVector(
    aBits,
    Vector.tabulate(n)(aAbs.io.ABSVAL.bit),
    bBits,
    Vector.tabulate(m)(bAbs.io.ABSVAL.bit)
  )

  val productBits = addSign(magnitudeProduct, aBits.last, bBits.last, signed)
  productBits.reverse.map(_.asBits).reduce(_ ## _)
