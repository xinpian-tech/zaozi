// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The external-IP Semantic-to-Stimulus loop on [[ExtAccumUT]]: the constraint solves through *imported*
  * SystemVerilog (circt-verilog → splice over the wrapper's extern), and the witness replays against the same RTL in
  * Verilator.
  */
object ExtAccumFormalGenTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("constraint through imported SV → witness → replay: SUM reaches 9 after two beats"):
      val dir = freshDir("ExtAccum-formalgen")
      val param = ExtAccumParameter(8)

      // 1. Lower the typed wrapper (extern inside), import the IP, splice, solve.
      val svFile = dir / "ext_accum.sv"
      os.makeDir.all(dir)
      os.write.over(svFile, ExtAccum.source)
      val model  = FormalUT.lowerGenerator(ExtAccumUT, param, dir)
      val ip     = SvImport.toHw(Seq(svFile), dir / "imported")
      val merged = SvImport.mergeForBmc(model.hw, ip)
      val txn    = solved(FormalUT.generate(model.copy(hw = merged), bound = 4))
      assert(txn.cycles >= 2)

      // 2. Bridge: the first two beats must sum to 9 — the constraint solved through the IP's own adder.
      val gen  = UTGenerator(ExtAccumUT, param, outputDirectory = dir)
      val stim = Stimulus.save(txn, gen.abi.spec, dir / "stimulus.txt")
      val a    = os.read(stim).linesIterator.map(_.trim.toLong).toVector
      assert((a.take(2).sum % 256) == 9)

      // 3. Replay against the same RTL (verilogSources carries it into the emitted testbench set).
      val tb  = gen.emitTestbench(dir, runCycles = txn.cycles + 1)
      val out = buildAndReplay(tb, dir, extraSources = Seq(svFile))
      assert(out.contains("SUM=9"))
