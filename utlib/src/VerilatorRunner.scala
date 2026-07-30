// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** One simulation run's outcome. */
final case class RunResult(
  exitCode: Int,
  stdout:   String,
  coverage: CoverageReport)

/** Builds and runs a generated harness under Verilator, then reads back coverage.
  *
  * `--assert` is what makes Verilator elaborate the SVA `cover property` statements at all; `--coverage-user` is what
  * makes it count them. Without both the run succeeds and reports zero coverage, which is the most confusing possible
  * failure — so both are always passed.
  */
object VerilatorRunner:

  def run(parameter: HarnessParameter, outDir: os.Path): RunResult =
    Toolchain.check()
    os.makeDir.all(outDir)
    val (harness, top) = Harness.emit(parameter, outDir)
    val buildDir       = outDir / "obj_dir"
    val coverageFile   = outDir / "coverage.dat"

    os.proc(
      Toolchain.verilator,
      "--binary",
      "--timing",
      "--assert",
      "--coverage-user",
      "--top-module",
      "top",
      "-Wno-fatal",
      "-Mdir",
      buildDir.toString,
      "-o",
      "simulation",
      harness.toString,
      top.toString
    ).call(cwd = outDir, check = true)

    val invocation = os
      .proc((buildDir / "simulation").toString, s"+verilator+coverage+file+$coverageFile")
      .call(cwd = outDir, check = false)

    val coverage =
      if os.exists(coverageFile) then parseCoverage(os.read(coverageFile))
      else CoverageReport(Map.empty)

    RunResult(invocation.exitCode, invocation.out.text(), coverage)

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
