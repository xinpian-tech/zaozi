// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.adder.default

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import scala.collection.immutable.SeqMap

// Generic radix prefix tree node. Leaves keep the original bit/column index;
// internal node indices are only sibling-group indices produced by grouping.
case class PrefixNode(leafs: Seq[PrefixNode], idx: Int)

// Post-order traversal: every child appears before the parent that consumes it.
// The P/G map construction relies on this order when folding internal nodes.
def flattenPrefixTree(n: PrefixNode): Seq[PrefixNode] = n.leafs match
  case Seq() => Seq(n)
  case _     => n.leafs.flatMap(flattenPrefixTree) ++ Seq(n)

// Root-to-last-leaf path. Group values on this path never feed a right sibling,
// so prefix implementations can skip building them as dead logic.
def rightmostSpine(n: PrefixNode): Seq[PrefixNode] = n.leafs match
  case Seq()    => Seq(n)
  case children => n +: rightmostSpine(children.last)

// Select original bit/column leaves from a shared flattened tree.
def prefixTreeLeaves(allNodes: Seq[PrefixNode]): Seq[PrefixNode] =
  allNodes.filter(_.leafs.isEmpty)

// Internal nodes whose group value is consumed by some carry-threading step.
// Nodes on the rightmost spine are intentionally pruned.
def prefixTreeInternalNodes(treeRoot: PrefixNode, allNodes: Seq[PrefixNode]): Seq[PrefixNode] =
  val spine = rightmostSpine(treeRoot).toSet
  allNodes.filter(n => n.leafs.nonEmpty && !spine.contains(n))

// Thread a carry from the root down to every leaf. `carryOut(prev, c)` is
// supplied by the caller because full adders use (P & c) | G, while
// incrementers only use P & c. Scanning over children.init produces the carries
// entering children and deliberately avoids building an unused carry leaving
// the last child.
def threadPrefixCarries(
  node:     PrefixNode,
  cin:      Referable[Bool]
)(carryOut: (PrefixNode, Referable[Bool]) => Referable[Bool]
): Seq[(Int, Referable[Bool])] =
  node.leafs match
    case Seq()    => Seq(node.idx -> cin)
    case children =>
      val carriesInto = children.init.scanLeft(cin)((c, prev) => carryOut(prev, c))
      children.zip(carriesInto).flatMap((ch, ci) => threadPrefixCarries(ch, ci)(carryOut))

// Build group propagates in post-order. The caller owns leaf semantics:
// full adders use A|B, while incrementers use A directly after constant-one
// propagation. Internal nodes are always the AND of their children.
def prefixTreePropagates(
  leaves:        Seq[PrefixNode],
  internal:      Seq[PrefixNode]
)(leafPropagate: PrefixNode => Referable[Bool]
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext
): SeqMap[PrefixNode, Referable[Bool]] =
  val pMap0 = SeqMap.from(leaves.map(n => n -> leafPropagate(n)))
  internal.foldLeft(pMap0)((p, nd) =>
    p + (nd -> nd.leafs.tail.foldLeft[Referable[Bool]](p(nd.leafs.head))((acc, ch) => acc & p(ch)))
  )

// Build group generates for the full-adder case. Incrementers deliberately do
// not call this helper; with B=1 and CI=0 constant propagation there is no
// generate network left, only the propagate/carry thread.
def prefixTreeGenerates(
  leaves:       Seq[PrefixNode],
  internal:     Seq[PrefixNode],
  propagates:   SeqMap[PrefixNode, Referable[Bool]]
)(leafGenerate: PrefixNode => Referable[Bool]
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext
): SeqMap[PrefixNode, Referable[Bool]] =
  val gMap0 = SeqMap.from(leaves.map(n => n -> leafGenerate(n)))
  internal.foldLeft(gMap0)((g, nd) =>
    g + (nd -> nd.leafs.tail.foldLeft[Referable[Bool]](g(nd.leafs.head))((acc, ch) => (propagates(ch) & acc) | g(ch)))
  )
