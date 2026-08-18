// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import scala.util.chaining.scalaUtilChainingOps

/** One simulation run's outcome. `log` keeps stdout and stderr in their original order. */
final case class RunResult(
  exitCode:  Int,
  log:       String,
  coverage:  CoverageReport,
  tracePath: Option[os.Path] = None)

/** The Verilator simulation backend. Assertions and user coverage are always enabled.
  *
  * The DUT is built as a Verilated model (`--cc --exe`) and the request's C++ frontend, linked against it, owns the
  * loop: it pokes the drive ports, advances the model, and peeks the probe ports, and it writes the waveform and
  * coverage before it exits.
  */
object Verilator extends Simulator:
  def name:   String = "verilator"
  def envVar: String = "VERILATOR"
  def binary: String = sys.env.getOrElse(envVar, "verilator")

  def simulate(request: SimulationRequest): RunResult =
    require(request.cppSources.nonEmpty, s"$name: the lib flow needs a C++ frontend to own the loop")
    val buildDir     = request.workDir / "obj_dir"
    val coverageFile = request.workDir / request.coverageFile
    val traceFile    = request.workDir / request.traceFile

    os.proc(
      binary,
      "--cc",
      "--exe",
      "--build",
      "--timing",
      "--assert",
      "--coverage-user",
      // `--trace` is what lets the frontend's VerilatedVcdC produce a dump; without it the
      // trace calls compile to no-ops.
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
      (request.sources ++ request.cppSources).map(_.toString)
    ).call(cwd = request.workDir, check = false, mergeErrIntoOut = true)
      .pipe { build =>
        // A compile failure is reported like any other failure rather than as
        // a raw SubprocessException, so its diagnostics reach the caller
        // instead of only the console.
        if build.exitCode != 0 then
          throw new RuntimeException(
            s"$name failed to build the testbench (exit ${build.exitCode}):\n${build.out.text()}"
          )
      }

    // The frontend owns the loop and writes coverage/trace itself, so the model is run plainly.
    val invocation = os
      .proc((buildDir / "simulation").toString)
      .call(cwd = request.workDir, check = false, mergeErrIntoOut = true)

    RunResult(
      exitCode = invocation.exitCode,
      log = invocation.out.text(),
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
