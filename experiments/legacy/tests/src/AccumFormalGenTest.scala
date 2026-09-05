// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The cross-transaction Semantic-to-Stimulus loop on [[AccumUT]]: a relation over three beats (pairwise distinct,
  * sum 12) → circt-bmc witness → stimulus → replay, where the accumulator's SUM confirms the generated beats.
  */
object AccumFormalGenTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("cross-beat constraint → witness → replay: three distinct beats summing to 12"):
      val dir = freshDir("Accum-formalgen")
      val param = AccumParameter(8)

      val txn = solved(FormalUT.generateGenerator(AccumUT, param, bound = 4, dir))
      assert(txn.cycles >= 3)

      val gen  = UTGenerator(AccumUT, param, outputDirectory = dir)
      val stim = Stimulus.save(txn, gen.abi.spec, dir / "stimulus.txt")
      val a    = os.read(stim).linesIterator.map(_.trim.toLong).toVector.take(3)
      assert(a.distinct.size == 3)
      assert(a.sum % 256 == 12)

      val tb  = gen.emitTestbench(dir, runCycles = txn.cycles + 1)
      val out = buildAndReplay(tb, dir)
      assert(out.contains("SUM=12"))
