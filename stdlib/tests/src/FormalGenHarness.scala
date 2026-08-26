// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

/** The scaffold every FormalGen test shares: the output/resource roots, the solve-or-fail extraction, and the one
  * Verilator build-and-replay incantation.
  */
object FormalGenHarness:
  val outputRoot: os.Path = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  val resources:  os.Path = os.Path(sys.props("zaozi.stdlib.testResources"), os.pwd)

  private val verilatorBin = sys.env.getOrElse("VERILATOR", "verilator")

  /** A fresh per-test artifact directory under the shared output root. */
  def freshDir(name: String): os.Path =
    val dir = outputRoot / name
    os.remove.all(dir)
    dir

  /** The witness, or a test failure naming the actual outcome. */
  def solved(outcome: GenerateOutcome): Trace = outcome match
    case GenerateOutcome.Generated(t) => t
    case other                        => throw java.lang.AssertionError(s"expected Generated, got $other")

  /** Build the emitted Model B testbench with Verilator and run it, returning the simulation's stdout. */
  def buildAndReplay(
    tb:            Testbench,
    dir:           os.Path,
    extraSources:  Seq[os.Path] = Seq.empty,
    extraIncludes: Seq[os.Path] = Seq.empty
  ): String =
    os.proc(
      Seq(verilatorBin, "--binary", "--timing", "--no-assert", "--top-module", tb.top, "-Wno-fatal", s"-I$dir") ++
        extraIncludes.map(p => s"-I$p") ++ (tb.sources ++ extraSources).map(_.toString) ++ Seq("-o", "sim")
    ).call(cwd = dir, check = true, mergeErrIntoOut = true)
    os.proc((dir / "obj_dir" / "sim").toString).call(cwd = dir, check = false, mergeErrIntoOut = true).out.text()
