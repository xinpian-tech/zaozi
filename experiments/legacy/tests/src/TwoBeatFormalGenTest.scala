// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The temporal Semantic-to-Stimulus loop on [[TwoBeatUT]]: a *sequence* constraint (`(A==3) ## (A==5)`, typed SVA) →
  * multi-cycle circt-bmc witness → `stimulus.txt` → Model B replay, where the DUT's `SEEN` pulses on the generated
  * two-beat pattern.
  */
object TwoBeatFormalGenTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("sequence constraint → multi-cycle witness → replay: SEEN pulses on the generated pattern"):
      val dir = freshDir("TwoBeat-formalgen")
      val param = TwoBeatParameter(8)

      // 1. Solve: the module asserts ¬((A==3) ## (A==5)); a violation is a trace containing the pattern.
      val txn = solved(FormalUT.generateGenerator(TwoBeatUT, param, bound = 4, dir))
      assert(txn.cycles >= 2)

      // 2. Bridge, checking the witness really holds the adjacent 3 → 5 pair.
      val gen  = UTGenerator(TwoBeatUT, param, outputDirectory = dir)
      val stim = Stimulus.save(txn, gen.abi.spec, dir / "stimulus.txt")
      val a    = os.read(stim).linesIterator.map(_.trim.toLong).toVector
      assert(a.sliding(2).exists { case Seq(x, y) => x == 3 && y == 5; case _ => false })

      // 3. Replay: the DUT observes the pattern — SEEN=1 on the cycle after the (3, 5) beat lands.
      val tb  = gen.emitTestbench(dir, runCycles = txn.cycles + 1)
      val out = buildAndReplay(tb, dir)
      assert(out.contains("SEEN=1"))
