// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*

case class BrentKungAdderParameter(width: Int, radix: Int) extends Parameter with PrefixAdderParameter:
  require(width > 0, "width must be positive")
  require(radix >= 2, "radix must be at least 2")

given upickle.default.ReadWriter[BrentKungAdderParameter] = upickle.default.macroRW

class BrentKungAdderLayers(parameter: BrentKungAdderParameter) extends PrefixAdderLayers(parameter)

class BrentKungAdderIO(parameter: BrentKungAdderParameter) extends PrefixAdderIO(parameter)

class BrentKungAdderProbe(parameter: BrentKungAdderParameter)
    extends DVBundle[BrentKungAdderParameter, BrentKungAdderLayers](parameter)

private def buildBrentKungPrefixTree(width: Int, radix: Int): PrefixNode =
  // width + 1 prefix columns: columns 0..width-1 are the real bits, and the
  // extra column `width` is the carry-out column (its A,B are 0). Including it
  // in the tree means the carry threaded INTO it is the adder carry-out — no
  // separate carry-out cell, and the root needs no group (G,P) of its own.
  val leafs = Seq.tabulate(width + 1) { i => PrefixNode(Seq.empty, i) }

  def reduceTree(layer: Seq[PrefixNode]): PrefixNode = layer match
    case Seq(n) => n
    case _      =>
      val nextLayer = layer
        .grouped(radix)
        .zipWithIndex
        .map((g, i) => PrefixNode(g, i))
        .toSeq
      reduceTree(nextLayer)

  reduceTree(leafs)

@generator
object BrentKungAdder
    extends Generator[
      BrentKungAdderParameter,
      BrentKungAdderLayers,
      BrentKungAdderIO,
      BrentKungAdderProbe
    ]:
  override def moduleName(p: BrentKungAdderParameter): String = s"BrentKungAdder_width${p.width}_radix${p.radix}"

  def architecture(parameter: BrentKungAdderParameter) =
    val io       = summon[Interface[BrentKungAdderIO]]
    val treeRoot = buildBrentKungPrefixTree(parameter.width, parameter.radix)
    connectPrefixAdder(io, treeRoot)
