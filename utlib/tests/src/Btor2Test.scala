// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*

import utest.*

/** The btor2 certification/transport engine: append predicate nodes over existing state
  * nodes, drive btormc, and read the verdict — the machinery that both validates a harvested
  * invariant (BMC-as-bad) and injects it (constraint) into a benchmark.
  *
  * Exercised on a self-contained toy so the engine is tested independently of any particular
  * benchmark: a 4-bit counter that increments every cycle, with a target it reaches at
  * depth 10 unless a strengthening constraint carves that trace away.
  */
object Btor2Test extends TestSuite:
  private val toy =
    """|1 sort bitvec 4
       |2 sort bitvec 1
       |3 constd 1 0
       |4 state 1 counter
       |5 constd 1 1
       |6 add 1 4 5
       |7 next 1 4 6
       |8 init 1 4 3
       |9 constd 1 10
       |10 eq 2 4 9
       |11 bad 10
       |""".stripMargin

  val tests: Tests = Tests:
    test("btormc finds the toy target, and a constraint removes it"):
      val design  = Btor2.parse(toy)
      val counter = design.stateNamed("counter").getOrElse(throw new java.lang.AssertionError("counter state missing"))
      assert(design.sortWidth(counter) == 4)

      // Baseline: the counter reaches 10.
      assert(Btor2.check(design, kmax = 20) == Btor2Result.Reachable(10))

      // Constrain counter < 5: the target at 10 is then unreachable.
      val constrained = design.withConstraint(Btor2Pred.Ult(counter, 5))
      assert(Btor2.check(constrained, kmax = 20) == Btor2Result.UnreachableWithin(20))

    test("a predicate can be checked as a fresh bad without touching the original"):
      val design = Btor2.parse(toy)
      val cnt    = design.stateNamed("counter").get
      // "counter >= 12" is reachable (at depth 12); "counter >= 5 while counter < 5" is not.
      assert(Btor2.checkPred(design, Btor2Pred.Uge(cnt, 12), kmax = 20) == Btor2Result.Reachable(12))
