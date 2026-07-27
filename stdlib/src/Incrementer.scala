// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

// same parameters as BrentKungAdder
case class BKAIncrementerParameter(width: Int, radix: Int = 4) extends Parameter with PrefixAdderParameter:
  require(width > 0, "width must be positive")
  require(radix >= 2, "radix must be at least 2")

given upickle.default.ReadWriter[BKAIncrementerParameter] = upickle.default.macroRW

class IncrementerLayers(parameter: BKAIncrementerParameter) extends PrefixAdderLayers(parameter)

class IncrementerIO(parameter: BKAIncrementerParameter) extends HWBundle(parameter):
  val A   = Flipped(Bits(parameter.width))
  val SUM = Aligned(Bits(parameter.width))

class IncrementerProbe(parameter: BKAIncrementerParameter)
    extends DVBundle[BKAIncrementerParameter, IncrementerLayers](parameter)

/** Radix carry-look-ahead incrementer, `SUM = A + 1` mod 2^width.
  *
  * Adding one means the carry into bit `b` is high exactly when all lower bits `A(0)..A(b-1)` are high. This is cheaper
  * than instantiating a full prefix adder with `B = 1`: there is no second input operand, no generate term `A & B`, and
  * no half-sum for `A ^ B`; the only carry condition is the prefix-AND of lower `A` bits.
  */
@generator
object Incrementer
    extends Generator[
      BKAIncrementerParameter,
      IncrementerLayers,
      IncrementerIO,
      IncrementerProbe
    ]:
  override def moduleName(p: BKAIncrementerParameter): String = s"Incrementer_width${p.width}_radix${p.radix}"

  def architecture(parameter: BKAIncrementerParameter) =
    val io       = summon[Interface[IncrementerIO]]
    val treeRoot = buildBrentKungPrefixTree(parameter.width, parameter.radix)
    val allNodes = flattenPrefixTree(treeRoot)
    val leaves   = prefixTreeLeaves(allNodes)
    val width    = leaves.map(_.idx).max + 1

    val internal   = prefixTreeInternalNodes(treeRoot, allNodes)
    val propagates = prefixTreePropagates(leaves, internal)(n => io.A.bit(n.idx))

    val leafCarries = threadPrefixCarries(treeRoot, true.B) { (child, c) =>
      propagates(child) & c
    }.toMap

    val sumBitMap = leaves.map(n => n.idx -> (propagates(n) ^ leafCarries(n.idx))).toMap
    val sumWord   = (1 until width).foldLeft(sumBitMap(0).asBits)((acc, i) => sumBitMap(i).asBits ## acc)

    val checkedSUM = Contract(sumWord) { sum =>
      val expected = (io.A.asUInt + 1.U(width)).asBits.bits(width - 1, 0)
      Ensure((sum === expected).I, "incrementer_matches_add")
    }

    io.SUM := checkedSUM
