// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** A declared coverage goal.
  *
  * `name` is the label attached to the emitted `verif.cover` op; it is the key the Verilator coverage report is matched
  * against, so it must be unique within a harness and must be a valid SystemVerilog identifier.
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
