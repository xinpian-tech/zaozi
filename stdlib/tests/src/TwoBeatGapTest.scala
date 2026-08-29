// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** Partial temporal specification: name the anchors, leave the spacing to the solver. */
object TwoBeatGapTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("a range delay is solved — the gap between anchors is the solver's choice"):
      val dir = freshDir("TwoBeatGap-formalgen")
      val txn = solved(FormalUT.generateGenerator(TwoBeatGapUT, TwoBeatParameter(8), bound = 6, dir))
      val gen = UTGenerator(TwoBeatGapUT, TwoBeatParameter(8), outputDirectory = dir)
      val a   = os.read(Stimulus.save(txn, gen.abi.spec, dir / "stimulus.txt"))
        .linesIterator.map(_.trim.toLong).toVector
      // 3 appears, and 5 follows it somewhere within the declared 1..3 cycle window.
      val i3  = a.indexOf(3L)
      assert(i3 >= 0)
      val gap = a.drop(i3 + 1).take(3).indexOf(5L)
      assert(gap >= 0)
      println(s"  witness: ${a.mkString(", ")}  (5 lands ${gap + 1} cycle(s) after 3)")
