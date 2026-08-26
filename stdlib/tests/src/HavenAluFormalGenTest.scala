// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** A real HAVEN benchmark IP (the 32-bit ALU) through the full loop: the constraint "completes with result 0xBEEF"
  * solves through the vendored RTL's XOR datapath and done pipeline, and the witness replays against the same RTL.
  */
object HavenAluFormalGenTest extends TestSuite:
  private val outputRoot   = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val resources    = os.Path(sys.props("zaozi.stdlib.testResources"), os.pwd)
  private val verilatorBin = sys.env.getOrElse("VERILATOR", "verilator")

  val tests: Tests = Tests:
    test("constraint through the HAVEN ALU → witness → replay: done with result 0xBEEF"):
      val dir    = outputRoot / "HavenAlu-formalgen"
      os.remove.all(dir)
      val param  = HavenAluParameter()
      val svFile = resources / "haven" / "alu_top.v"

      val (wrapperHw, top) = FormalUT.lowerGenerator(HavenAluUT, param, dir, delayedDrives = Seq("A"))
      val ip               = SvImport.toHw(Seq(svFile), dir / "imported")
      val merged           = SvImport.mergeForBmc(wrapperHw, ip)
      val txn              = FormalUT.generate(merged, top, bound = 6) match
        case GenerateOutcome.Generated(t) => t
        case other                        => throw java.lang.AssertionError(s"expected Generated, got $other")
      assert(txn.cycles >= 3)

      // The witness must contain a beat whose packed operands XOR to 0xBEEF.
      val gen  = UTGenerator(HavenAluUT, param, outputDirectory = dir)
      val stim = Stimulus.save(txn, gen.abi.spec, dir / "stimulus.txt")
      val a    = os.read(stim).linesIterator.map(_.trim.toLong).toVector
      assert(a.exists(v => ((v & 0xffffffffL) ^ ((v >>> 32) & 0xffffffffL)) == 0xbeefL))

      val tb = gen.emitTestbench(dir, runCycles = txn.cycles + 2)
      os.proc(
        verilatorBin,
        "--binary",
        "--timing",
        "--no-assert",
        "--top-module",
        tb.top,
        "-Wno-fatal",
        "-I" + dir.toString,
        (tb.sources :+ svFile).map(_.toString),
        "-o",
        "sim"
      ).call(cwd = dir, check = true, mergeErrIntoOut = true)
      val out =
        os.proc((dir / "obj_dir" / "sim").toString).call(cwd = dir, check = false, mergeErrIntoOut = true).out.text()
      assert(out.contains("RESULT=48879") && out.contains("DONE=1"))
