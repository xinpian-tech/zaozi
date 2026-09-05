// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The four typed semantic kinds — value, relation, state, temporal — composed in one [[SemAccumUT]] intent, solved
  * to a single witness and replayed: two distinct nonzero beats summing to 9, then a 1, then a 2, ending at SUM 12.
  */
object SemAccumFormalGenTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("a four-kind intent pins the witness: distinct nonzero pair summing 9, then 1, then 2"):
      val dir = freshDir("SemAccum-formalgen")
      val param = AccumParameter(8)

      val txn = solved(FormalUT.generateGenerator(SemAccumUT, param, bound = 7, dir, delayedDrives = Seq("A")))

      val gen  = UTGenerator(SemAccumUT, param, outputDirectory = dir)
      val stim = Stimulus.save(txn, gen.abi.spec, dir / "stimulus.txt")
      val a    = os.read(stim).linesIterator.map(_.trim.toLong).toVector
      // Somewhere in the stream: b1, b2, 1, 2 with b1 ≠ b2, both nonzero, b1 + b2 = 9.
      val hit  = a.sliding(4).exists {
        case Seq(b1, b2, x, y) => b1 != b2 && b1 != 0 && b2 != 0 && (b1 + b2) % 256 == 9 && x == 1 && y == 2
        case _                 => false
      }
      assert(hit)

      val tb  = gen.emitTestbench(dir, runCycles = txn.cycles + 2)
      val out = buildAndReplay(tb, dir)
      assert(out.contains("SUM=12"))
