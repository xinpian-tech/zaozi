// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.utlib.*

import utest.*

/** The counterexample-replay bridge: a property is checked against the solver's view of the
  * constrained stimulus space, and a violation is not just reported — its model is replayed
  * through the simulator as a concrete stimulus, confirming the counterexample is feasible
  * in a dynamic environment (the Magellan contract).
  */
object PropertyCheckTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  private def generator(name: String) =
    UTGenerator(
      MixedInput,
      MixedInputParameter(4),
      cycles = 1,
      outputDirectory = outputRoot / name
    )

  val tests: Tests = Tests:
    test("a property implied by the constraints is proven"):
      // MixedInput's constraints pin uint at cycle 0 to 9, so uint < 16 must hold.
      val outcome = generator("property-proven").check:
        val io = summon[ConstraintInterface[MixedInputIO]]
        io.uint.at(0) < 16.S
      assert(outcome == PropertyOutcome.Proven())

    test("a violated property yields a counterexample that replays in simulation"):
      // The constraints pin uint to 9, so `uint === 5` is violated — by exactly that stimulus.
      val outcome = generator("property-falsified").check:
        val io = summon[ConstraintInterface[MixedInputIO]]
        io.uint.at(0) === 5.S
      outcome match
        case PropertyOutcome.Falsified(stimulus, replay) =>
          assert(stimulus.io.uint.values == Vector(BigInt(9)))
          assert(replay.exitCode == 0)
          assert(replay.log.contains("HARNESS-DONE"))
        case other                                       =>
          throw new java.lang.AssertionError(s"expected Falsified, got $other")
