// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import scala.collection.immutable.SeqMap
import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class PrefixNode(leafs: Seq[PrefixNode], idx: Int)

trait PrefixAdderParameter:
  def width: Int

class PrefixAdderLayers[P <: Parameter & PrefixAdderParameter](parameter: P) extends LayerInterface(parameter):
  def layers = Seq.empty

class PrefixAdderIO[P <: Parameter & PrefixAdderParameter](parameter: P) extends HWBundle[P](parameter):
  val A   = Flipped(Bits(parameter.width))
  val B   = Flipped(Bits(parameter.width))
  val CI  = Flipped(Bool())
  val CO  = Aligned(Bool())
  val SUM = Aligned(Bits(parameter.width))

def connectPrefixAdder[I <: PrefixAdderIO[?]](
  io:       Interface[I],
  treeRoot: PrefixNode
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext
) =
  def flatten(n: PrefixNode): Seq[PrefixNode] = n.leafs match
    case Seq() => Seq(n)
    case _     => n.leafs.flatMap(flatten) ++ Seq(n)

  val allNodes = flatten(treeRoot)
  val leaves   = allNodes.filter(_.leafs.isEmpty)
  val width    = leaves.map(_.idx).max

  // Bit i of A/B, zero-extended: real bit for i < width, else constant 0. The
  // extra column i == width is the carry-out column — a real leaf of the tree
  // with A=B=0, whose sum bit (0^0)^carry_width = carry_width IS the adder CO.
  def aBit(i: Int): Referable[Bool] = if i < width then io.A.bit(i) else false.B
  def bBit(i: Int): Referable[Bool] = if i < width then io.B.bit(i) else false.B

  // A node's group (G, P) is formed only where some ancestor's carry-threading
  // will consume it. The rightmost spine — root, then last-child down to the top
  // column — never feeds a right sibling and tops out at the CO column, so no
  // node on it needs a group (the root included). Every node OFF the spine is
  // either a non-last child (its group threads the next sibling's carry) or the
  // last child of a formed node (its group folds into that parent's group), so
  // it is needed. Skipping the spine is exactly what matches the ref at every
  // width; the down-sweep is unaffected (it threads carries through all nodes).
  def rightmostSpine(n: PrefixNode): Seq[PrefixNode] = n.leafs match
    case Seq()    => Seq(n)
    case children => n +: rightmostSpine(children.last)
  val spine = rightmostSpine(treeRoot).toSet
  val internal = allNodes.filter(n => n.leafs.nonEmpty && !spine.contains(n))

  // ── leaves: OR-propagate, and reuse P,G for the half-sum (1 XOR/bit) ──────
  //   P = A|B (OR2), G = A&B (AND2). A^B = P·!G via NOT+AND2 — reusing the very
  //   P,G the carry tree already needs, instead of a dedicated second XOR.
  val pMap0: SeqMap[PrefixNode, Referable[Bool]] = SeqMap.from(leaves.map(n => n -> (aBit(n.idx) | bBit(n.idx))))
  val gMap0: SeqMap[PrefixNode, Referable[Bool]] = SeqMap.from(leaves.map(n => n -> (aBit(n.idx) & bBit(n.idx))))
  val hsMap = SeqMap.from(leaves.map(n => n -> ((!gMap0(n)) & pMap0(n))))

  // ── up-sweep: group propagate = AND of children P; group generate = the
  //   associative dot folded as a chain of AO21 ((A&B)|C) cells (one per extra
  //   child), instead of expanding into an OR2/AND2 nest. Folds over any arity.
  val propagates = internal.foldLeft(pMap0)((p, nd) =>
    p + (nd -> nd.leafs.tail.foldLeft[Referable[Bool]](p(nd.leafs.head))((acc, ch) => acc & p(ch)))
  )
  val generates  = internal.foldLeft(gMap0)((g, nd) =>
    g + (nd -> nd.leafs.tail.foldLeft[Referable[Bool]](g(nd.leafs.head))((acc, ch) => (propagates(ch) & acc) | g(ch)))
  )

  // ── down-sweep: thread the true carry into each leaf. carryOut(child, c) =
  //   (P_child & c) | G_child = one AO21. We scan over children.INIT, so one
  //   AO21 is built per *non-last* child; the last child's carry-out equals the
  //   node's own carry-out, which the PARENT already computes — so we don't
  //   duplicate it. (The original `.scanLeft(cin)(…).init` left that cell built
  //   but unused: a dead AO21 per node.)
  def threadCarries(node: PrefixNode, cin: Referable[Bool]): Seq[(Int, Referable[Bool])] =
    node.leafs match
      case Seq()    => Seq(node.idx -> cin)
      case children =>
        val carriesInto = children.init.scanLeft(cin)((c, prev) => (propagates(prev) | c) & generates(prev))
        children.zip(carriesInto).flatMap((ch, ci) => threadCarries(ch, ci))

  // The tree spans width+1 columns, so the root carries no group (G,P) of its
  // own — its children's carries are threaded straight from CI. That makes the
  // root just another internal node in the down-sweep: one unified recursion,
  // no carry-out cell. The carry into the CO column already IS the carry-out.
  val leafCarries = threadCarries(treeRoot, io.CI).toMap

  // ── sum: SUM[i] = (A_i ^ B_i) ^ carry_i for the width real columns. The CO
  //   column's own sum bit is (0^0) ^ carry_width = carry_width = the carry-out,
  //   emitted by the same half-sum/XOR2 pattern as every other column.
  val sumBitMap = leaves.filter(_.idx < width).map(n => n.idx -> (hsMap(n) ^ leafCarries(n.idx))).toMap
  val sumWord   = (1 until width).foldLeft(sumBitMap(0).asBits)((acc, i) => sumBitMap(i).asBits ## acc)
  val coLeaf    = leaves.find(_.idx == width).get
  val carryOut  = hsMap(coLeaf) ^ leafCarries(width)

  val (checkedCO, checkedSUM) = Contract((carryOut, sumWord)) { case (co, sum) =>
    val observed = (co.asBits ## sum).asUInt
    val expected = (io.A.asUInt + io.B.asUInt + io.CI.asBits.asUInt).asBits.bits(width, 0).asUInt
    Ensure((observed === expected).I, Some("prefix_adder_matches_add"))
  }

  io.SUM := checkedSUM
  io.CO  := checkedCO
