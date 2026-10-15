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

/** Width-specialized multiplication for when one operand is a single bit (`aWidth == 1` or `bWidth == 1`), selected
  * purely from the parameter -- the product is just that bit times the other operand. Signed or unsigned is chosen at
  * runtime by `signed`. A 1x1 is the degenerate case where the vector is one bit wide.
  *
  * Sign-magnitude datapath: the product magnitude `|A| * |B|` is one row of AND gates (the single bit gates either the
  * raw vector for unsigned or the vector magnitude for signed), then the sign is applied by conditionally negating
  * through the incrementer. `|vector|` comes from [[me.jiuyang.stdlib.AbsVal]]; a 1x1 needs no magnitude at all (it is
  * a single AND). The result is returned to `Multiplier`, whose top-level `Contract` proves correctness.
  */
private[default] def oneByVectorMultiply(
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
  require(parameter.aWidth == 1 || parameter.bWidth == 1, "oneByVectorMultiply requires a 1-bit operand")

  val n     = parameter.aWidth
  val m     = parameter.bWidth
  val zero  = false.B
  val aBits = Vector.tabulate(n)(a.bit)
  val bBits = Vector.tabulate(m)(b.bit)

  // one * vector as a single AND row: the single bit gates either the raw vector
  // (unsigned) or the vector magnitude (signed), with one zero on top.
  def oneByVector(
    bit:             Referable[Bool],
    vector:          Vector[Referable[Bool]],
    vectorMagnitude: Vector[Referable[Bool]]
  ): Vector[Referable[Bool]] =
    val row = vector.zip(vectorMagnitude).map { (vectorBit, magnitudeBit) =>
      val unsignedBit = bit & vectorBit
      val signedBit   = bit & magnitudeBit
      signed ? (signedBit, unsignedBit)
    }
    row :+ zero

  // The single-bit side is whichever operand is 1 bit wide; 1x1 needs no AbsVal.
  val magnitudeProduct =
    if n == 1 && m == 1 then Vector(aBits.head & bBits.head, zero)
    else if n == 1 then
      val bAbs = AbsVal.instantiate(AbsValParameter(m))
      bAbs.io.A := b
      oneByVector(aBits.head, bBits, Vector.tabulate(m)(bAbs.io.ABSVAL.bit))
    else
      val aAbs = AbsVal.instantiate(AbsValParameter(n))
      aAbs.io.A := a
      oneByVector(bBits.head, aBits, Vector.tabulate(n)(aAbs.io.ABSVAL.bit))

  val productBits = addSign(magnitudeProduct, aBits.last, bBits.last, signed)
  productBits.reverse.map(_.asBits).reduce(_ ## _)
