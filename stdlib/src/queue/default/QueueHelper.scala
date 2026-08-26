// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.queue.default

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

private[default] object QueueHelper:
  /** Minimum number of bits required to represent values in `[0, value)`. */
  def bitWidth(value: Int): Int =
    require(value > 0, s"bitWidth input must be positive, got $value")
    math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(value - 1))

  /** Physical RAM depth used by the dual-clock controller. For a non-power-of-two depth, `calc_e_depth` selects the
    * next permitted even value strictly above it: odd depths add one entry and even depths add two.
    */
  def effectiveDepth(depth: Int): Int =
    if (1 << bitWidth(depth)) == depth then depth else depth + 2 - (depth % 2)

  def binaryToGray(
    binary: Referable[UInt],
    width:  Int
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Node[UInt] =
    val shifted = (false.B.asBits ## binary.asBits.bits(width - 1, 1)).asUInt
    (binary.asBits ^ shifted.asBits).asUInt

  def grayToBinary(
    gray:  Referable[UInt],
    width: Int
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Node[UInt] =
    (0 until width).reverse
      .map(bit => gray.asBits.bits(width - 1, bit).xorR.asBits)
      .reduce((msb, lsb) => msb ## lsb)
      .asUInt

/** Compile-time geometry for mapping an arbitrary logical depth onto a power-of-two binary pointer space. */
private[default] final case class PointerGeometry(depth: Int):
  val addressWidth: Int = QueueHelper.bitWidth(depth)
  val pointerWidth: Int = QueueHelper.bitWidth(depth + 1)

  val realLeftOver:  Int = (1 << pointerWidth) - depth
  val modulus:       Int =
    if realLeftOver == depth then depth * 2 else depth + 2 - (depth % 2)
  val leftOverCount: Int = (1 << pointerWidth) - modulus
  val shift:         Int =
    if leftOverCount == 0 then 0 else Integer.numberOfTrailingZeros(leftOverCount)
  val residual:      Int =
    if shift == 0 then leftOverCount else leftOverCount >> shift

  val needsCorrection: Boolean = leftOverCount != 0 && realLeftOver != depth
