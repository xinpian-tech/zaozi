// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

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
