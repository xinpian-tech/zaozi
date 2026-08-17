// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.smtlib.{Solver, Z3}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FirrtlDialectApi}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi as given_LTLDialectApi, DialectApi as LTLDialectApi}
import org.llvm.circt.scalalib.capi.dialect.verif.{
  given_DialectApi as given_VerifDialectApi,
  DialectApi as VerifDialectApi
}
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, given}

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
    val driverPath = outDir / s"${Driver.topModuleName}.sv"
    os.write.over(driverPath, Driver.topString(topModule, trace, traceFile))
    SimulationRequest(
      sources = Seq(emitted.primary, driverPath),
      workDir = outDir,
      topModule = Driver.topModuleName,
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
