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

/** Width-specialized multiplication for when one operand is exactly two bits wide (and the other is at least two),
  * selected purely from the parameter. Signed or unsigned is chosen at runtime by `signed`.
  *
  * A 2x2 uses a hand-scheduled structure ([[twoByTwo]]); a 2xm / nx2 uses two partial-product rows summed by a
  * Brent-Kung adder ([[twoByVector]]). Both produce the magnitude product `|A| * |B|` (signed) or the plain product
  * (unsigned), which [[me.jiuyang.stdlib.multiplier.addSign]] then signs. `Multiplier` proves the returned result with
  * its top-level `Contract`.
  */
private[default] def twoByVectorMultiply(
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
    (parameter.aWidth == 2 && parameter.bWidth >= 2) || (parameter.bWidth == 2 && parameter.aWidth >= 2),
    "twoByVectorMultiply requires a 2-bit operand and the other at least 2 bits"
  )

  val n     = parameter.aWidth
  val m     = parameter.bWidth
  val zero  = false.B
  val aBits = Vector.tabulate(n)(a.bit)
  val bBits = Vector.tabulate(m)(b.bit)

  // 2x2: |A| coefficients are derived inline from A's bits; the unsigned product
  // is a tiny carry-save schedule. Both muxed by `signed`.
  def twoByTwo(bMagnitude: Vector[Referable[Bool]]): Vector[Referable[Bool]] =
    val notA0  = !aBits(0)
    val notA1  = !aBits(1)
    val term0  = aBits(1) & notA0
    val term1  = notA1 & aBits(0)
    val term2  = aBits(0) & aBits(1)
    val coeff0 = term1 | term2

    val signedRow0    = bMagnitude.map(coeff0 & _)
    val signedRow1    = bMagnitude.map(term0 & _)
    val signedProduct = Vector(
      signedRow0(0),
      signedRow0(1) | signedRow1(0),
      signedRow1(1),
      zero
    )

    val u1              = aBits(0) & bBits(0)
    val u2              = aBits(0) & bBits(1)
    val u3              = aBits(1) & bBits(0)
    val u4              = aBits(1) & bBits(1)
    val u5              = compressAdd32(zero, zero, u1)
    val u6              = compressAdd32(u3, u2, u5.carry)
    val u7              = compressAdd32(u4, zero, u6.carry)
    val unsignedProduct = Vector(u5.sum, u6.sum, u7.sum, u7.carry)

    signedProduct.zip(unsignedProduct).map { (signedBit, unsignedBit) =>
      signed ? (signedBit, unsignedBit)
    }

  // 2-bit operand times a vector: two partial-product rows, the upper shifted by
  // one, summed with carry-out through the Brent-Kung adder.
  def twoByVector(
    twoBits:         Vector[Referable[Bool]],
    twoMagnitude:    Vector[Referable[Bool]],
    vector:          Vector[Referable[Bool]],
    vectorMagnitude: Vector[Referable[Bool]]
  ): Vector[Referable[Bool]] =
    val twoCoefficients    = twoBits.zip(twoMagnitude).map { (bit, magnitudeBit) => signed ? (magnitudeBit, bit) }
    val vectorCoefficients = vector.zip(vectorMagnitude).map { (bit, magnitudeBit) => signed ? (magnitudeBit, bit) }
    val row0               = vectorCoefficients.map(twoCoefficients(0) & _)
    val row1               = vectorCoefficients.map(twoCoefficients(1) & _)
    val (sum, carry)       = brentKungAdd(row0.drop(1) :+ zero, row1)
    row0.head +: sum :+ carry

  val magnitudeProduct =
    if n == 2 && m == 2 then
      val bAbs = AbsVal.instantiate(AbsValParameter(m))
      bAbs.io.A := b
      twoByTwo(Vector.tabulate(m)(bAbs.io.ABSVAL.bit))
    else
      val aAbs = AbsVal.instantiate(AbsValParameter(n))
      aAbs.io.A := a
      val bAbs = AbsVal.instantiate(AbsValParameter(m))
      bAbs.io.A := b
      val aMagnitude = Vector.tabulate(n)(aAbs.io.ABSVAL.bit)
      val bMagnitude = Vector.tabulate(m)(bAbs.io.ABSVAL.bit)
      if n == 2 then twoByVector(aBits, aMagnitude, bBits, bMagnitude)
      else twoByVector(bBits, bMagnitude, aBits, aMagnitude)

  val productBits = addSign(magnitudeProduct, aBits.last, bBits.last, signed)
  productBits.reverse.map(_.asBits).reduce(_ ## _)
