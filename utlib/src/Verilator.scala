// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** One simulation run's outcome.
  *
  * `stdout` and `stderr` are kept apart because they carry different things: a FIRRTL `printf` lowers to a write on
  * file descriptor 2, so the transaction trace arrives on `stderr` while `$display` from the generated top arrives on
  * `stdout`. [[log]] is the two interleaved for when you just want to read the run.
  */
final case class RunResult(
  exitCode: Int,
  stdout:   String,
  stderr:   String,
  coverage: CoverageReport,
  tracePath: Option[os.Path] = None):

  /** Everything the run printed, whichever stream it used. */
  def log: String = stdout + stderr

  /** Lines of the transaction trace, if the run emitted one. */
  def traceLines: Seq[String] = log.linesIterator.filter(_.contains("[txn]")).toSeq

/** The Verilator simulation backend.
  *
  * `--assert` is what makes Verilator elaborate the SVA `cover property` statements at all; `--coverage-user` is what
  * makes it count them. Without both, the run succeeds and reports zero coverage — the most confusing possible failure
  * — so both are always passed.
  */
object Verilator extends Simulator:
  def name:   String = "verilator"
  def envVar: String = "VERILATOR"
  def binary: String = sys.env.getOrElse(envVar, "verilator")

  def simulate(request: SimulationRequest): RunResult =
    val buildDir     = request.workDir / "obj_dir"
    val coverageFile = request.workDir / request.coverageFile
    val traceFile    = request.workDir / request.traceFile

    os.proc(
      binary,
      "--binary",
      "--timing",
      "--assert",
      "--coverage-user",
      // `--trace` is what makes $dumpfile/$dumpvars in the generated top do
      // anything; without it they are silently ignored.
      if request.trace then Seq("--trace", "--trace-structs") else Seq.empty,
      "--top-module",
      request.topModule,
      "-Wno-fatal",
      // Layer bind files sit next to the main file and are pulled in by the
      // `include` firtool emitted, so the work directory is on the include path.
      "-I" + request.workDir.toString,
      "-Mdir",
      buildDir.toString,
      "-o",
      "simulation",
      request.sources.map(_.toString)
    ).call(cwd = request.workDir, check = true)

    val invocation = os
      .proc((buildDir / "simulation").toString, s"+verilator+coverage+file+$coverageFile")
      .call(cwd = request.workDir, check = false, stderr = os.Pipe)

    RunResult(
      exitCode = invocation.exitCode,
      stdout = invocation.out.text(),
      stderr = invocation.err.text(),
      coverage = if os.exists(coverageFile) then parseCoverage(os.read(coverageFile)) else CoverageReport(Map.empty),
      tracePath = Option.when(request.trace && os.exists(traceFile))(traceFile)
    )

  /** Parse a Verilator `coverage.dat` file.
    *
    * Verified against Verilator 5.048 output. A data line is:
    * {{{
    * C '<field>\u0001<field>\u0001...' <count>
    * }}}
    * and each field is `key\u0002value`. For an SVA cover point Verilator emits the keys `f` (file), `l` (line), `n`
    * (column), `t` (type, "user"), `page`, `o` (the cover label) and `h` (the hierarchical path).
    *
    * The label lives under `o` — *not* under `n`, which is a column number. The `h` path is the fallback, since it ends
    * with the same label.
    *
    * Counts for the same label are summed, because one declared coverpoint yields several records when its module is
    * instantiated more than once.
    */
  def parseCoverage(dat: String): CoverageReport =
    val record = raw"""^C\s+'(.*)'\s+(\d+)\s*$$""".r
    val hits   = dat
      .split('\n')
      .iterator
      .map(_.trim)
      .collect { case record(fields, count) =>
        val keyed = fields
          .split('\u0001')
          .flatMap(field =>
            field.split('\u0002') match
              case Array(key, value) => Some(key -> value)
              case _                 => None
          )
          .toMap
        val name  = keyed
          .get("o")
          .orElse(keyed.get("h").map(_.split('.').last))
          .getOrElse("<unnamed>")
        name -> count.toInt
      }
      .toSeq
      .groupMapReduce(_._1)(_._2)(_ + _)
    CoverageReport(hits)
