// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** A constraint-driven unit test for one hardware component.
  *
  * Implementors describe *what must be true* of the stimulus and *what must be covered* — never a concrete poke
  * sequence. [[run]] solves the constraints, elaborates a harness around the DUT, simulates it under Verilator, and
  * reports which coverpoints were reached.
  *
  * This is the payoff of writing constraints rather than cases: the same declaration re-solves against a different
  * seed, a longer sequence, or a changed DUT, and the solver — not the author — works out a stimulus that satisfies it.
  */
trait UnitTest:
  /** The DUT's transaction surface. */
  def iface: DutInterface

  /** DUT payload width, in bits. */
  def width: Int

  /** Sequence length, in cycles. */
  def cycles: Int

  /** The coverage goals this test claims to reach. */
  def coverpoints: Seq[Coverpoint]

  /** The stimulus constraints. */
  def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit

  /** Z3 seed. Change it to explore a different satisfying stimulus. */
  val seed: Int = 0

  /** Solve the constraints into a concrete stimulus sequence. */
  final def solve(): SolvedStimulus =
    val outer     = this
    val txnSolver = new TxnSolver:
      def iface:         DutInterface                                = outer.iface
      def cycles:        Int                                         = outer.cycles
      def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit = outer.constraints()
      override val seed: Int                                         = outer.seed
    txnSolver.solve()

  /** Emit a per-cycle transaction trace through `sim.print`. Off by default; it is a debugging aid, not a check. */
  val txnTrace: Boolean = false

  /** The elaboration parameter for this test's harness. */
  final def harnessParameter: HarnessParameter =
    HarnessParameter(width = width, stimulus = solve(), coverpoints = coverpoints, txnTrace = txnTrace)

  /** Solve, elaborate, simulate. Artifacts are written under `outDir`.
    *
    * Set `trace` to also capture a VCD waveform of the run — useful when a coverpoint is unexpectedly missed and the
    * solved stimulus alone does not explain why.
    */
  final def run(outDir: os.Path, trace: Boolean = false): RunResult =
    VerilatorRunner.run(harnessParameter, outDir, trace)

  /** Throw with a readable summary when the run failed or missed a coverpoint. */
  final def requireCoverage(result: RunResult): Unit =
    if result.exitCode != 0 then
      throw new AssertionError(
        s"simulation exited with ${result.exitCode}; output was:\n${result.stdout}"
      )
    val missed = result.coverage.missed(coverpoints)
    if missed.nonEmpty then
      val detail = missed.map(p => s"  - ${p.name}: ${p.description}").mkString("\n")
      throw new AssertionError(
        s"${missed.size} of ${coverpoints.size} coverpoints were not hit:\n$detail\n" +
          s"observed hits: ${result.coverage.hits}"
      )
