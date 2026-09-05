// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** A real HAVEN benchmark IP (the 32-bit ALU) through the full loop: the constraint "completes with result 0xBEEF"
  * solves through the vendored RTL's XOR datapath and done pipeline, and the witness replays against the same RTL.
  */
object HavenAluFormalGenTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("constraint through the HAVEN ALU → witness → replay: done with result 0xBEEF"):
      val dir = freshDir("HavenAlu-formalgen")
      val param  = HavenAluParameter()
      val svFile = resources / "haven" / "alu_top.v"

      val model  = FormalUT.lowerGenerator(HavenAluUT, param, dir, delayedDrives = Seq("A"))
      val ip     = SvImport.toHw(Seq(svFile), dir / "imported")
      val merged = SvImport.mergeForBmc(model.hw, ip)
      val txn    = solved(FormalUT.generate(model.copy(hw = merged), bound = 6))
      assert(txn.cycles >= 3)

      // The witness must contain a beat whose packed operands XOR to 0xBEEF.
      val gen  = UTGenerator(HavenAluUT, param, outputDirectory = dir)
      val stim = Stimulus.save(txn, gen.abi.spec, dir / "stimulus.txt")
      val a    = os.read(stim).linesIterator.map(_.trim.toLong).toVector
      assert(a.exists(v => ((v & 0xffffffffL) ^ ((v >>> 32) & 0xffffffffL)) == 0xbeefL))

      val tb  = gen.emitTestbench(dir, runCycles = txn.cycles + 2)
      val out = buildAndReplay(tb, dir, extraSources = Seq(svFile))
      assert(out.contains("RESULT=48879") && out.contains("DONE=1"))
