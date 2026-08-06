// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.smtlib

import utest.*

object SolverSpec extends TestSuite:
  val tests: Tests = Tests:
    test("Z3 is available and accepts seeded SMT-LIB"):
      Z3.check()
      val output = Z3.run("(set-logic QF_LIA)\n(check-sat)\n", seed = 17)
      assert(output.trim == "sat")

    test("a missing solver reports its environment override"):
      val missing = new Solver:
        def name:                                               String = "missing"
        def binary:                                             String = "definitely-not-a-real-solver"
        def envVar:                                             String = "MISSING_SOLVER"
        def run(smtlib: String, seed: Int, timeoutMillis: Int): String = ""

      assert(!missing.available)
      val message = intercept[RuntimeException](missing.check()).getMessage
      assert(message.contains("MISSING_SOLVER"))
      assert(message.contains("nix develop"))
