// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.adder.default

import me.jiuyang.stdlib.adder.{PrefixAdderParameter, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

class IncrementerLayers(parameter: PrefixAdderParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IncrementerIO(parameter: PrefixAdderParameter) extends HWBundle(parameter):
  val a   = Flipped(Bits(parameter.width))
  val sum = Aligned(Bits(parameter.width))

class IncrementerProbe(parameter: PrefixAdderParameter)
    extends DVBundle[PrefixAdderParameter, IncrementerLayers](parameter)

/** Radix carry-look-ahead incrementer, `SUM = A + 1` mod 2^width.
  *
  * Adding one means the carry into bit `b` is high exactly when all lower bits `A(0)..A(b-1)` are high. This is cheaper
  * than instantiating a full prefix adder with `B = 1`: there is no second input operand, no generate term `A & B`, and
  * no half-sum for `A ^ B`; the only carry condition is the prefix-AND of lower `A` bits.
  */
@generator
object Incrementer
    extends Generator[
      PrefixAdderParameter,
      IncrementerLayers,
      IncrementerIO,
      IncrementerProbe
    ]:
  override def moduleName(p: PrefixAdderParameter): String = s"Incrementer_width${p.width}_radix${p.radix}"

  def architecture(parameter: PrefixAdderParameter) =
    val io       = summon[Interface[IncrementerIO]]
    val treeRoot = buildBrentKungPrefixTree(parameter.width, parameter.radix)
    val allNodes = flattenPrefixTree(treeRoot)
    val leaves   = prefixTreeLeaves(allNodes)
    val width    = leaves.map(_.idx).max + 1

    val internal   = prefixTreeInternalNodes(treeRoot, allNodes)
    val propagates = prefixTreePropagates(leaves, internal)(n => io.a.bit(n.idx))

    val leafCarries = threadPrefixCarries(treeRoot, true.B) { (child, c) =>
      propagates(child) & c
    }.toMap

    val sumBitMap = leaves.map(n => n.idx -> (propagates(n) ^ leafCarries(n.idx))).toMap
    val sumWord   = (1 until width).foldLeft(sumBitMap(0).asBits)((acc, i) => sumBitMap(i).asBits ## acc)

    val checkedSUM = Contract(sumWord) { sum =>
      val expected = (io.a.asUInt + 1.U(width)).asBits.bits(width - 1, 0)
      Ensure((sum === expected).I, "incrementer_matches_add")
    }

    io.sum := checkedSUM
