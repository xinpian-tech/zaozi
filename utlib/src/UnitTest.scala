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

  /** DUT payload width, in bits — *derived* from [[iface]] rather than stated again.
    *
    * The solver bounds payloads by each port's `payloadWidth` while the harness renders them as literals of this width.
    * When those were two independent declarations they could disagree, and the disagreement surfaced only after a full
    * solve, as a width error from deep inside the Zaozi DSL that named neither of them.
    */
  final def width: Int =
    val widths = iface.ports.map(_.payloadWidth).distinct
    require(
      widths.size == 1,
      s"${iface.dutName}: all ports must share one payload width, got " +
        iface.ports.map(p => s"${p.name}=${p.payloadWidth}").mkString(", ")
    )
    widths.head

  /** Sequence length, in cycles. */
  def cycles: Int

  /** The coverage goals this test claims to reach.
    *
    * These are an *expectation*, checked against what the run reported — not an elaboration input. The harness emits
    * its whole catalogue of coverpoints every time; a test names the subset it must hit. See
    * `me.jiuyang.utlib.FifoHarness` for the catalogue and the signals each one binds to.
    */
  def coverpoints: Seq[Coverpoint]

  /** Every coverpoint the harness can emit. An expectation outside this set is a mistake, not a coverage hole. */
  def catalogue: Seq[Coverpoint] = FifoCoverpoints.all

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

  /** Emit a per-cycle transaction trace. Off by default; it is a debugging aid, not a check.
    *
    * The trace is a `printf` over typed signals, which firtool lowers to `sv.fwrite` — it does not travel the sim
    * dialect. See [[printf]].
    */
  val txnTrace: Boolean = false

  /** The elaboration parameter for this test's harness. */
  final def harnessParameter: HarnessParameter =
    HarnessParameter(width = width, stimulus = solve(), txnTrace = txnTrace)

  /** Solve, elaborate, simulate. Artifacts are written under `outDir`.
    *
    * Set `trace` to also capture a VCD waveform of the run — useful when a coverpoint is unexpectedly missed and the
    * solved stimulus alone does not explain why.
    */
  final def run(outDir: os.Path, trace: Boolean = false): RunResult =
    validateCoverpoints()
    Simulation.run(harnessParameter, outDir, trace)

  /** Reject expectations the harness could never satisfy.
    *
    * A mistyped name would otherwise be indistinguishable from a genuine coverage hole: it is simply never hit.
    */
  final def validateCoverpoints(): Unit =
    val known   = catalogue.map(_.name).toSet
    val unknown = coverpoints.map(_.name).filterNot(known.contains)
    if unknown.nonEmpty then
      throw new IllegalArgumentException(
        s"unknown coverpoint(s): ${unknown.mkString(", ")}\n" +
          s"the harness declares: ${catalogue.map(_.name).sorted.mkString(", ")}"
      )

  /** Throw with a readable summary when the run failed or missed a coverpoint. */
  final def requireCoverage(result: RunResult): Unit =
    validateCoverpoints()
    if result.exitCode != 0 then
      throw new AssertionError(
        s"simulation exited with ${result.exitCode}; output was:\n${result.log}"
      )
    val missed = result.coverage.missed(coverpoints)
    if missed.nonEmpty then
      val detail = missed.map(p => s"  - ${p.name}: ${p.description}").mkString("\n")
      throw new AssertionError(
        s"${missed.size} of ${coverpoints.size} coverpoints were not hit:\n$detail\n" +
          s"observed hits: ${result.coverage.hits}"
      )

  /** Solve once and persist the stimulus to `path` as JSON — a *frozen* case.
    *
    * Freezing pins the exact solved sequence so a regression replays it via
    * [[runStimulus]] without re-solving: fast, and immune to solver-version or
    * seed drift. A subsequent failure then means the DUT changed, not the
    * search. Commit the file next to the test. Returns the frozen stimulus.
    */
  final def freeze(path: os.Path): SolvedStimulus =
    val stimulus = solve()
    os.write.over(path, upickle.default.write(stimulus, indent = 2))
    stimulus

  /** Elaborate and simulate a specific stimulus, bypassing the solver — the
    * replay half of [[freeze]]. Load a frozen case with [[UnitTest.loadStimulus]].
    */
  final def runStimulus(
    stimulus: SolvedStimulus,
    outDir:   os.Path,
    trace:    Boolean = false
  ): RunResult =
    require(
      stimulus.dut == iface.dutName,
      s"frozen stimulus is for '${stimulus.dut}', but this test's DUT is '${iface.dutName}'"
    )
    Simulation.run(HarnessParameter(width = width, stimulus = stimulus, txnTrace = txnTrace), outDir, trace)

object UnitTest:

  /** Load a frozen stimulus previously written by [[UnitTest.freeze]]. */
  def loadStimulus(path: os.Path): SolvedStimulus =
    upickle.default.read[SolvedStimulus](os.read(path))
