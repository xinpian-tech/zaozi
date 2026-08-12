// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*

import utest.*

/** Trace values entering the solve itself: previously observed stimuli become MaxSMT soft
  * constraints, so the solver searches *away from* what simulation has already exercised
  * (diversity) or *toward* a reference trace (warm-started reproduction). The hard
  * constraints are untouched — bias can never admit a stimulus the constraints reject.
  */
object TraceBiasTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  private def generator(name: String) =
    UTGenerator(
      CoveredUnit,
      CoveredUnitParameter(4),
      cycles = 1,
      outputDirectory = outputRoot / name
    )

  val tests: Tests = Tests:
    test("an away bias yields a stimulus differing from every reference"):
      val dut      = generator("bias-away")
      val baseline = dut.solve()
      val second   = dut.solveBiased(TraceBias.Away, Seq(baseline), roundSeed = 1)
      assert(second.io.a.values != baseline.io.a.values)
      val third    = dut.solveBiased(TraceBias.Away, Seq(baseline, second), roundSeed = 2)
      assert(third.io.a.values != baseline.io.a.values)
      assert(third.io.a.values != second.io.a.values)

    test("a toward bias reproduces the reference under a different seed"):
      val dut      = generator("bias-toward")
      val baseline = dut.solve()
      val replayed = dut.solveBiased(TraceBias.Toward, Seq(baseline), roundSeed = 7)
      assert(replayed.io.a.values == baseline.io.a.values)

    test("away-biased re-solving closes coverage without author hints"):
      // `a` has 16 possible values and cover_magic needs a === 13: each round's stimulus
      // must differ from every earlier one, so the loop reaches 13 within the budget with
      // nobody telling the solver how.
      val closure = generator("coverage-diversity").closeCoverageByDiversity(
        goals = Seq("cover_magic"),
        maxRounds = 16
      )
      assert(closure.closed)
      val played = closure.rounds.flatMap(_.stimulus).map(_.io.a.values)
      assert(played.distinct.size == played.size)
