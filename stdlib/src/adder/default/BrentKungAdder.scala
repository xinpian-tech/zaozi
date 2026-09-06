// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.adder.default

import java.lang.foreign.Arena

import me.jiuyang.stdlib.adder.{AdderIO, AdderImpl, AdderLayers, AdderProbe, PrefixAdderParameter, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import scala.collection.immutable.SeqMap

private def buildBrentKungPrefixTree(width: Int, radix: Int): PrefixNode =
  // width here is including the carry-out column for the adder,
  // so width prefix columns: columns 0..width-1 are the real bits, and the
  // extra column `width` is the carry-out column (its A,B are 0). Including it
  // in the tree means the carry threaded INTO it is the adder carry-out -- no
  // separate carry-out cell, and the root needs no group (G,P) of its own.
  val leafs = Seq.tabulate(width) { i => PrefixNode(Seq.empty, i) }

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
      PrefixAdderParameter,
      AdderLayers[PrefixAdderParameter],
      AdderIO[PrefixAdderParameter],
      AdderProbe[PrefixAdderParameter]
    ]:
  override def moduleName(p: PrefixAdderParameter): String = s"BrentKungAdder_width${p.width}_radix${p.radix}"

  def architecture(parameter: PrefixAdderParameter) =
    val io       = summon[Interface[AdderIO[PrefixAdderParameter]]]
    val treeRoot = buildBrentKungPrefixTree(parameter.width + 1, parameter.radix)
    val allNodes = flattenPrefixTree(treeRoot)
    val leaves   = prefixTreeLeaves(allNodes)
    val width    = leaves.map(_.idx).max

    // Bit i of A/B, zero-extended: real bit for i < width, else constant 0. The
    // extra column i == width is the carry-out column — a real leaf of the tree
    // with A=B=0, whose sum bit (0^0)^carry_width = carry_width IS the adder CO.
    def aBit(i: Int): Referable[Bool] = if i < width then io.a.bit(i) else false.B
    def bBit(i: Int): Referable[Bool] = if i < width then io.b.bit(i) else false.B

    // A node's group (G, P) is formed only where some ancestor's carry-threading
    // will consume it. The rightmost spine — root, then last-child down to the top
    // column — never feeds a right sibling and tops out at the CO column, so no
    // node on it needs a group (the root included). Every node OFF the spine is
    // either a non-last child (its group threads the next sibling's carry) or the
    // last child of a formed node (its group folds into that parent's group), so
    // it is needed. Skipping the spine is exactly what matches the ref at every
    // width; the down-sweep is unaffected (it threads carries through all nodes).
    val internal = prefixTreeInternalNodes(treeRoot, allNodes)

    // ── leaves and up-sweep ──────────────────────────────────────────────────
    //   Leaves use OR-propagate and reuse P,G for the half-sum (1 XOR/bit):
    //   P = A|B (OR2), G = A&B (AND2), A^B = P·!G via NOT+AND2.
    //
    // ── up-sweep: group propagate = AND of children P; group generate = the
    //   associative dot folded as a chain of AO21 ((A&B)|C) cells (one per extra
    //   child), instead of expanding into an OR2/AND2 nest. Folds over any arity.
    val propagates = prefixTreePropagates(leaves, internal)(n => aBit(n.idx) | bBit(n.idx))
    val generates  = prefixTreeGenerates(leaves, internal, propagates)(n => aBit(n.idx) & bBit(n.idx))
    val hsMap      = SeqMap.from(leaves.map(n => n -> ((!generates(n)) & propagates(n))))

    // ── down-sweep: thread the true carry into each leaf. carryOut(child, c) =
    //   (P_child & c) | G_child = one AO21. We scan over children.INIT, so one
    //   AO21 is built per *non-last* child; the last child's carry-out equals the
    //   node's own carry-out, which the PARENT already computes — so we don't
    //   duplicate it. (The original `.scanLeft(cin)(…).init` left that cell built
    //   but unused: a dead AO21 per node.)
    // The tree spans width+1 columns, so the root carries no group (G,P) of its
    // own — its children's carries are threaded straight from CI. That makes the
    // root just another internal node in the down-sweep: one unified recursion,
    // no carry-out cell. The carry into the CO column already IS the carry-out.
    val leafCarries = threadPrefixCarries(treeRoot, io.ci) { (child, c) =>
      (propagates(child) & c) | generates(child)
    }.toMap

    // ── sum: SUM[i] = (A_i ^ B_i) ^ carry_i for the width real columns. The CO
    //   column's own sum bit is (0^0) ^ carry_width = carry_width = the carry-out,
    //   emitted by the same half-sum/XOR2 pattern as every other column.
    val sumBitMap = leaves.filter(_.idx < width).map(n => n.idx -> (hsMap(n) ^ leafCarries(n.idx))).toMap
    val sumWord   = (1 until width).foldLeft(sumBitMap(0).asBits)((acc, i) => sumBitMap(i).asBits ## acc)
    val coLeaf    = leaves.find(_.idx == width).get
    val carryOut  = hsMap(coLeaf) ^ leafCarries(width)

    val (checkedCO, checkedSUM) = Contract((carryOut, sumWord)) { case (co, sum) =>
      val observed = (co.asBits ## sum).asUInt
      val expected = (io.a.asUInt + io.b.asUInt + io.ci.asBits.asUInt).asBits.bits(width, 0).asUInt
      Ensure((observed === expected).I, "prefix_adder_matches_add")
    }

    io.sum := checkedSUM
    io.co  := checkedCO

given AdderImpl with
  def apply(
    parameter: PrefixAdderParameter
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[AdderIO[PrefixAdderParameter]] =
    val io      = Wire(new AdderIO(parameter))
    val adderIO = BrentKungAdder.instantiate(parameter).io

    adderIO.a  := io.a
    adderIO.b  := io.b
    adderIO.ci := io.ci
    io.co      := adderIO.co
    io.sum     := adderIO.sum
    io
