// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** Names that are shared between a DUT, its harness, and the generated testbench.
  *
  * Each of these is a string that has to agree across places the compiler cannot relate — a FIRRTL layer name is
  * matched by the `include` the top emits, and a trace marker written in one file is grepped in another. Declaring them
  * once is what keeps a rename from silently producing a dangling include or an empty trace.
  */
object Names:
  /** The layer that white-box probes and probe-bound coverpoints live under. */
  val verificationLayer: String = "Verification"

  /** Prefix every transaction-trace line carries. */
  val txnMarker: String = "[txn]"

/** A declared coverage goal — the *expectation* side of coverage.
  *
  * `name` matches the label a harness attached to one of its `Cover(…)` ops. That string is irreducible: it becomes a
  * SystemVerilog cover label and is the key Verilator reports back in `coverage.dat`. The coverpoint's *condition*, by
  * contrast, lives in the harness as ordinary typed code over real signal references — see
  * `me.jiuyang.utlib.FifoHarness`.
  */
final case class Coverpoint(
  name:        String,
  description: String)

object Coverpoint:
  given upickle.default.ReadWriter[Coverpoint] = upickle.default.macroRW

/** Coverpoint hit counts collected from one simulation run. */
final case class CoverageReport(hits: Map[String, Int]):

  /** Whether `name` was hit at least once. Unknown names count as missed. */
  def hit(name: String): Boolean = hits.getOrElse(name, 0) > 0

  /** The declared coverpoints this run did not reach. */
  def missed(points: Seq[Coverpoint]): Seq[Coverpoint] = points.filterNot(p => hit(p.name))

  /** Fraction of declared coverpoints hit, in `[0.0, 1.0]`. Empty ⇒ 1.0. */
  def rate(points: Seq[Coverpoint]): Double =
    if points.isEmpty then 1.0
    else points.count(p => hit(p.name)).toDouble / points.size.toDouble
