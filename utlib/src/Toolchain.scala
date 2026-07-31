// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** An external tool the framework drives. */
trait Tool:
  /** Short name used to select the tool and to report errors. */
  def name: String

  /** The executable, looked up on `PATH` unless overridden by [[envVar]]. */
  def binary: String

  /** Environment variable that overrides [[binary]] with an absolute path. */
  def envVar: String

  /** Whether the executable resolves. */
  final def available: Boolean =
    try os.proc("which", binary).call(check = false).exitCode == 0
    catch case _: Throwable => false

  /** Fail early, with a remediation hint, when the executable is missing. */
  final def check(): Unit =
    if !available then
      throw new RuntimeException(
        s"$name not found on PATH (looked for `$binary`). " +
          "Enter the dev shell with `nix develop .` from the zaozi root, " +
          s"or set $envVar to an absolute path."
      )

/** What a [[Simulator]] is asked to build and run. */
final case class SimulationRequest(
  /** SystemVerilog sources to compile, in order. */
  sources:      Seq[os.Path],
  /** Directory to build and run in; auxiliary files are resolved relative to it. */
  workDir:      os.Path,
  /** Name of the top module to elaborate. */
  topModule:    String,
  /** Whether to capture a waveform. */
  trace:        Boolean,
  /** Where the waveform should land, relative to `workDir`. */
  traceFile:    String,
  /** Where coverage should land, relative to `workDir`. */
  coverageFile: String)

/** A simulation backend.
  *
  * The framework talks to simulators only through this interface: build the given sources, run the result, and report
  * back the log, the coverage, and the waveform. Everything simulator-specific — command-line flags, how coverage is
  * requested, and the format it comes back in — lives behind it.
  *
  * Verilator is the only implementation today. A VCS backend would implement this same interface (its own flags, and
  * `urg`/`vdb` parsing in place of `coverage.dat`) and register itself in [[Toolchain.simulators]]; nothing above this
  * line would change.
  */
trait Simulator extends Tool:
  /** Build and run `request`, returning the outcome. */
  def simulate(request: SimulationRequest): RunResult

/** An SMT solver backend.
  *
  * Kept behind an interface for the same reason as [[Simulator]]: seeding syntax and command-line shape are
  * solver-specific, everything above is not.
  */
trait Solver extends Tool:
  /** Solve `smtlib`, returning the solver's raw stdout.
    *
    * @param seed
    *   makes the search reproducible; how it is expressed is the solver's business
    * @param timeoutMillis
    *   per-query wall-clock budget
    */
  def solve(smtlib: String, seed: Int, timeoutMillis: Int = 5000): String

/** The tools this framework runs against.
  *
  * Selection is by name through the environment, so a run can be pointed at a different backend without a rebuild:
  * `UTLIB_SIMULATOR=verilator`, `UTLIB_SOLVER=z3`.
  */
object Toolchain:
  /** Registered simulator backends. Adding VCS means adding it to this map. */
  val simulators: Map[String, Simulator] = Map(Verilator.name -> Verilator)

  /** Registered solver backends. */
  val solvers: Map[String, Solver] = Map(Z3.name -> Z3)

  /** The simulator this run uses. */
  val simulator: Simulator = select("UTLIB_SIMULATOR", simulators, Verilator)

  /** The solver this run uses. */
  val solver: Solver = select("UTLIB_SOLVER", solvers, Z3)

  /** Fail early if either tool is missing, rather than midway through a run. */
  def check(): Unit =
    simulator.check()
    solver.check()

  private def select[T <: Tool](variable: String, registry: Map[String, T], default: T): T =
    sys.env.get(variable) match
      case None       => default
      case Some(want) =>
        registry.getOrElse(
          want.toLowerCase,
          throw new RuntimeException(
            s"$variable=$want is not a known backend; available: ${registry.keys.toSeq.sorted.mkString(", ")}"
          )
        )
