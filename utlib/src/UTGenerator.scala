// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.smtlib.{Solver, Z3}
import me.jiuyang.smtlib.tpe.{Bool as SMTBool, Referable}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FirrtlDialectApi}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi as given_LTLDialectApi, DialectApi as LTLDialectApi}
import org.llvm.circt.scalalib.capi.dialect.verif.{
  given_DialectApi as given_VerifDialectApi,
  DialectApi as VerifDialectApi
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, given}

import java.lang.foreign.Arena

/** Constraint solving and the default simulation harness for one Zaozi generator. */
final class UTGenerator[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]
] private (
  val dut:             Generator[PARAM, L, I, P] & HasUT[PARAM, I],
  val parameter:       PARAM,
  val cycles:          Int,
  val outputDirectory: os.Path,
  val seed:            Int,
  val solverBackend:   Solver,
  val timeoutCycles: Int):

  require(cycles > 0, "cycles must be positive")
  require(timeoutCycles > cycles + 4, "timeoutCycles must leave room for reset and all stimulus cycles")

  def solve(): SolvedStimulus[I] = ConstraintSolver.solve(dut, parameter, cycles, seed, solverBackend)

  /** Solve with previously observed stimuli biasing the search (see [[TraceBias]]): `Away`
    * diversifies from what simulation already exercised, `Toward` warm-starts around a
    * reference trace. Hard constraints are untouched — this cannot admit a stimulus the
    * constraints reject, only choose among the admitted ones.
    */
  def solveBiased(
    bias:       TraceBias,
    references: Seq[SolvedStimulus[I]],
    roundSeed:  Int = seed
  ): SolvedStimulus[I] =
    require(references.nonEmpty, "solveBiased needs at least one reference stimulus")
    ConstraintSolver.solveBiased(dut, parameter, cycles, roundSeed, solverBackend)(bias, references.map(_.data))

  def freeze(path: os.Path = outputDirectory / "stimulus.json"): SolvedStimulus[I] =
    val stimulus = solve()
    os.makeDir.all(path / os.up)
    os.write.over(path, upickle.default.write(stimulus.data, indent = 2))
    stimulus

  def loadStimulus(path: os.Path = outputDirectory / "stimulus.json"): SolvedStimulus[I] =
    SolvedStimulus(upickle.default.read[StimulusData](os.read(path)))

  def run(
    outDir:    os.Path = outputDirectory,
    trace:     Boolean = false,
    traceFile: String = "trace.vcd"
  ): RunResult =
    runStimulus(solve(), outDir, trace, traceFile)

  def runStimulus(
    stimulus:  SolvedStimulus[I],
    outDir:    os.Path = outputDirectory,
    trace:     Boolean = false,
    traceFile: String = "trace.vcd"
  ): RunResult =
    Simulation.run(simulationRequest(stimulus, outDir, trace, traceFile))

  /** Check a property over the constrained stimulus space; replay any counterexample.
    *
    * The property ranges over the same per-cycle input symbols as [[HasUT.constraints]], so a
    * SAT model of `constraints ∧ ¬property` *is* a stimulus. It is replayed through the
    * simulator — with tracing on, so the waveform documents the violation — before being
    * reported as [[PropertyOutcome.Falsified]].
    */
  def check(
    property: (Arena, Context, Block, ConstraintInterface[I]) ?=> Referable[SMTBool],
    outDir:   os.Path = outputDirectory,
    trace:    Boolean = true
  ): PropertyOutcome[I] =
    import ConstraintSolver.RefuteOutcome
    ConstraintSolver.refute(dut, parameter, cycles, seed, solverBackend)(property) match
      case RefuteOutcome.Holds()               => PropertyOutcome.Proven()
      case RefuteOutcome.Undecided(status)     => PropertyOutcome.Unknown(status)
      case RefuteOutcome.Refuted(cex)          =>
        PropertyOutcome.Falsified(cex, runStimulus(cex, outDir, trace))

  /** Drive coverage closed: solve, simulate, and re-solve toward whatever is still missed.
    *
    * Round 0 plays the baseline stimulus. Each following round takes the first still-missed
    * goal, conjoins its hint with the DUT's constraints, bumps the seed, and simulates the
    * result — a run targeting one goal often hits others on the way, so hits accumulate
    * across rounds. A goal whose hint is unsatisfiable is recorded and abandoned: retrying a
    * proven-UNSAT query with a different seed cannot help. The loop stops when every goal is
    * hit, every pursuable goal is abandoned, or `maxRounds` hinted rounds have run.
    */
  def closeCoverage(
    goals:     Seq[CoverageGoal[I]],
    maxRounds: Int = 4,
    outDir:    os.Path = outputDirectory,
    trace:     Boolean = false
  ): CoverageClosure[I] =
    require(goals.nonEmpty, "closeCoverage needs at least one goal")
    require(maxRounds >= 1, "maxRounds must be positive")
    val rounds = Seq.newBuilder[CoverageRound[I]]
    var hits   = Map.empty[String, Int]

    def record(run: RunResult): Unit =
      hits = run.coverage.hits.foldLeft(hits) { case (acc, (name, count)) =>
        acc.updated(name, acc.getOrElse(name, 0) + count)
      }

    val baseline = solve()
    val baseRun  = runStimulus(baseline, outDir / "round0", trace)
    record(baseRun)
    rounds += CoverageRound(None, seed, Some(baseline), Some(baseRun))

    var abandoned = Set.empty[String]
    def pursuable  = goals.filter(goal => hits.getOrElse(goal.name, 0) == 0 && !abandoned.contains(goal.name))

    var round = 1
    var queue = pursuable
    while round <= maxRounds && queue.nonEmpty do
      val goal      = queue.head
      val roundSeed = seed + round
      ConstraintSolver.solveWith(dut, parameter, cycles, roundSeed, solverBackend)(goal.hint) match
        case Some(stimulus) =>
          val run = runStimulus(stimulus, outDir / s"round$round", trace)
          record(run)
          rounds += CoverageRound(Some(goal.name), roundSeed, Some(stimulus), Some(run))
        case None           =>
          abandoned += goal.name
          rounds += CoverageRound(Some(goal.name), roundSeed, None, None)
      round += 1
      // Rotate so one stubborn goal does not starve the rest of the budget.
      val remaining = pursuable
      queue = remaining.filterNot(_.name == goal.name) ++ remaining.filter(_.name == goal.name)

    CoverageClosure(
      rounds.result(),
      hits,
      goals.map(_.name).filterNot(name => hits.getOrElse(name, 0) > 0).toSet
    )

  /** Close coverage with no author-written hints: every round re-solves biased *away* from
    * all previously played stimuli, so the solver walks fresh corners of the constrained
    * space until the goals are hit or the budget runs out. This is the trace-in-the-solve
    * counterpart of [[closeCoverage]]: the knowledge of where to go next comes from the
    * traces already seen, not from the test author.
    */
  def closeCoverageByDiversity(
    goals:     Seq[String],
    maxRounds: Int = 16,
    outDir:    os.Path = outputDirectory,
    trace:     Boolean = false
  ): CoverageClosure[I] =
    require(goals.nonEmpty, "closeCoverageByDiversity needs at least one goal")
    require(maxRounds >= 1, "maxRounds must be positive")
    val rounds = Seq.newBuilder[CoverageRound[I]]
    var hits   = Map.empty[String, Int]

    def record(run: RunResult): Unit =
      hits = run.coverage.hits.foldLeft(hits) { case (acc, (name, count)) =>
        acc.updated(name, acc.getOrElse(name, 0) + count)
      }
    def missed = goals.filter(goal => hits.getOrElse(goal, 0) == 0)

    val baseline = solve()
    val baseRun  = runStimulus(baseline, outDir / "round0", trace)
    record(baseRun)
    rounds += CoverageRound(None, seed, Some(baseline), Some(baseRun))

    var played = Vector(baseline)
    var round  = 1
    while round <= maxRounds && missed.nonEmpty do
      val roundSeed = seed + round
      val stimulus  = solveBiased(TraceBias.Away, played, roundSeed)
      val run       = runStimulus(stimulus, outDir / s"round$round", trace)
      record(run)
      played :+= stimulus
      rounds += CoverageRound(None, roundSeed, Some(stimulus), Some(run))
      round += 1

    CoverageClosure(rounds.result(), hits, missed.toSet)

  /** Elaborate, link and lower the generic harness without running a simulator. */
  def simulationRequest(
    stimulus:  SolvedStimulus[I],
    outDir:    os.Path = outputDirectory,
    trace:     Boolean = false,
    traceFile: String = "trace.vcd"
  ): SimulationRequest =
    require(
      stimulus.dut == dut.moduleName(parameter),
      s"stimulus is for ${stimulus.dut}, not ${dut.moduleName(parameter)}"
    )
    require(stimulus.cycles == cycles, s"stimulus has ${stimulus.cycles} cycles, expected $cycles")
    require(traceFile.nonEmpty, "traceFile must not be empty")
    os.makeDir.all(outDir)

    val harnessParameter = DefaultUTHarnessParameter(stimulus.data, timeoutCycles, trace, traceFile)
    val harness          = new DefaultUTHarnessGenerator(dut, parameter)
    val topModule        = harness.moduleName(harnessParameter)
    val moduleDir        = outDir / s"mlir_${harnessParameter.hashCode.toHexString}"
    os.makeDir.all(moduleDir)

    elaborate(harness, harnessParameter, moduleDir)
    val modules = os.list(moduleDir).filter(_.ext == "mlirbc").sortBy(_.last)
    require(modules.nonEmpty, s"elaboration produced no .mlirbc files under $moduleDir")
    val linked  = moduleDir / "linked.mlir"
    os.proc(
      Seq("firld", s"--base-circuit=$topModule", "--no-mangle") ++
        modules.map(_.toString) ++ Seq("-o", linked.toString)
    ).call()

    val emitted    = SvEmitter.writeVerilog(SvEmitter.verilogString(os.read.bytes(linked)), outDir)
    val controller = emitted.splitFiles.getOrElse(
      SimulationController.verilogSourceName,
      throw IllegalStateException(
        s"CIRCT did not emit ${SimulationController.verilogSourceName} from the inline Verilog source"
      )
    )
    SimulationRequest(
      sources = Seq(emitted.primary, controller),
      workDir = outDir,
      topModule = topModule,
      trace = trace,
      traceFile = traceFile,
      coverageFile = "coverage.dat"
    )

  private def elaborate(
    harness:   DefaultUTHarnessGenerator[PARAM, L, I, P],
    parameter: DefaultUTHarnessParameter,
    outDir:    os.Path
  ): Unit =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      Elaboration.inOutputDirectory(outDir) {
        harness.dumpMlirbc(parameter)
      }
      summon[Context].destroy()
    finally arena.close()

object UTGenerator:
  def apply[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    dut:             Generator[PARAM, L, I, P] & HasUT[PARAM, I],
    parameter:       PARAM,
    cycles:          Int,
    outputDirectory: os.Path,
    seed:            Int = 0,
    solverBackend:   Solver = Z3,
    timeoutCycles:   Option[Int] = None
  ): UTGenerator[PARAM, L, I, P] =
    new UTGenerator(
      dut,
      parameter,
      cycles,
      outputDirectory,
      seed,
      solverBackend,
      timeoutCycles.getOrElse(cycles + 16)
    )
