// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.multiplier.default

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import me.jiuyang.stdlib.{BKAIncrementerParameter, BrentKungAdder, BrentKungAdderParameter, Incrementer, PrefixAdderIO}

/** The sum/carry of a 3:2 compressor (full adder). */
private[default] case class CompressorOutput(sum: Referable[Bool], carry: Referable[Bool])

/** Ordinary-operation 3:2 compressor: `sum = a ^ b ^ c`, `carry = ab + ac + bc`. */
private[default] def compressAdd32(
  a: Referable[Bool],
  b: Referable[Bool],
  c: Referable[Bool]
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext
): CompressorOutput =
  CompressorOutput(a ^ b ^ c, (a & b) | (a & c) | (b & c))

/** Add two equal-width, LSB-first bit rows through a Brent-Kung adder; returns the `width` sum bits and the carry-out.
  */
private[default] def brentKungAdd(
  row0: Vector[Referable[Bool]],
  row1: Vector[Referable[Bool]]
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext
): (Vector[Referable[Bool]], Referable[Bool]) =
  require(row0.nonEmpty && row0.size == row1.size, "brentKungAdd needs two equal, non-empty rows")
  val adder = BrentKungAdder.instantiate(BrentKungAdderParameter(row0.size, 4))
  val addIO = adder.io.asInstanceOf[Interface[PrefixAdderIO[BrentKungAdderParameter]]]
  addIO.A  := row0.reverse.map(_.asBits).reduce[Referable[Bits]](_ ## _)
  addIO.B  := row1.reverse.map(_.asBits).reduce[Referable[Bits]](_ ## _)
  addIO.CI := false.B
  (Vector.tabulate(row0.size)(addIO.SUM.bit), addIO.CO)

/** Sign-magnitude finalize: given a magnitude product and the two operand sign bits, negate the magnitude (`~mag + 1`
  * through the incrementer) iff `signed` and the signs differ, otherwise pass the magnitude through.
  */
private[default] def addSign(
  productMagnitude: Vector[Referable[Bool]],
  aSign:            Referable[Bool],
  bSign:            Referable[Bool],
  signed:           Referable[Bool]
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext
): Vector[Referable[Bool]] =
  val negate      = signed & (aSign ^ bSign)
  val incrementer = Incrementer.instantiate(BKAIncrementerParameter(productMagnitude.size))
  incrementer.io.A := productMagnitude.reverse.map(bit => (!bit).asBits).reduce[Referable[Bits]](_ ## _)
  productMagnitude.zipWithIndex.map { (magnitudeBit, index) =>
    negate ? (incrementer.io.SUM.bit(index), magnitudeBit)
  }
