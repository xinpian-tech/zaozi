// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.HWInterface

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Cover labels and hit counts reported by the simulator. Cover operations themselves belong in the DUT architecture.
  */
final case class CoverageReport(hits: Map[String, Int]):
  def hit(name: String): Boolean = hits.getOrElse(name, 0) > 0

/** One coverage goal for [[UTGenerator.closeCoverage]]: the cover label to reach, and the extra
  * constraints that nudge the solver toward a stimulus reaching it.
  *
  * The hint is the test author's knowledge of *how* to provoke the cover point, written in the
  * same constraint language as [[HasUT.constraints]]. It is conjoined with the DUT's own
  * constraints, never replacing them — a hint that contradicts them is reported as an
  * unreachable goal rather than silently widening the stimulus space.
  */
final class CoverageGoal[I <: HWInterface[?]](
  val name: String,
  val hint: (Arena, Context, Block, ConstraintInterface[I]) ?=> Unit)

object CoverageGoal:
  def apply[I <: HWInterface[?]](
    name: String
  )(
    hint: (Arena, Context, Block, ConstraintInterface[I]) ?=> Unit
  ): CoverageGoal[I] = new CoverageGoal(name, hint)

/** One solve-and-simulate round of the closure loop. `goal` is empty for the baseline round;
  * `stimulus` is empty when the goal's hint contradicted the constraints (UNSAT).
  */
final case class CoverageRound[I <: HWInterface[?]](
  goal:     Option[String],
  seed:     Int,
  stimulus: Option[SolvedStimulus[I]],
  result:   Option[RunResult])

/** The outcome of a closure loop: every round in order, the accumulated hit counts, and the
  * goals no round reached.
  */
final case class CoverageClosure[I <: HWInterface[?]](
  rounds: Seq[CoverageRound[I]],
  hits:   Map[String, Int],
  missed: Set[String]):
  def closed: Boolean = missed.isEmpty
