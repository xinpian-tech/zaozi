// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The cross-transaction Semantic-to-Stimulus loop on [[AccumUT]]: a relation over three beats (pairwise distinct,
  * sum 12) → circt-bmc witness → stimulus → replay, where the accumulator's SUM confirms the generated beats.
  */
object AccumFormalGenTest extends TestSuite:
  private val outputRoot   = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val verilatorBin = sys.env.getOrElse("VERILATOR", "verilator")

  val tests: Tests = Tests:
    test("cross-beat constraint → witness → replay: three distinct beats summing to 12"):
      val dir   = outputRoot / "Accum-formalgen"
      os.remove.all(dir)
      val param = AccumParameter(8)

      val txn = FormalUT.generateGenerator(AccumUT, param, bound = 4, dir) match
        case GenerateOutcome.Generated(t) => t
        case other                        => throw java.lang.AssertionError(s"expected Generated, got $other")
      assert(txn.cycles >= 3)

      val gen  = UTGenerator(AccumUT, param, outputDirectory = dir)
      val stim = Stimulus.save(txn, gen.abi.spec, dir / "stimulus.txt")
      val a    = os.read(stim).linesIterator.map(_.trim.toLong).toVector.take(3)
      assert(a.distinct.size == 3)
      assert(a.sum % 256 == 12)

      val tb = gen.emitTestbench(dir, runCycles = txn.cycles + 1)
      os.proc(
        verilatorBin,
        "--binary",
        "--timing",
        "--no-assert",
        "--top-module",
        tb.top,
        "-Wno-fatal",
        "-I" + dir.toString,
        tb.sources.map(_.toString),
        "-o",
        "sim"
      ).call(cwd = dir, check = true, mergeErrIntoOut = true)
      val out =
        os.proc((dir / "obj_dir" / "sim").toString).call(cwd = dir, check = false, mergeErrIntoOut = true).out.text()
      assert(out.contains("SUM=12"))
