// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.SolverContext
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.Z3Status
import utest.*

object ExportSMTLIBSpec extends TestSuite:
  val tests = Tests:
    test("debug exports the same sequence and still executes"):
      withSMTContext:
        var output          = ""
        var calls           = 0
        given SolverContext = new SolverContext(debug = Some(text => { calls += 1; output = text }))
        val result          = solver {
          val a = smtValue(Bool)
          smtAssert(a)
          smtCheck
          smtPush(1)
          smtAssert(!a)
          smtCheck
          smtPop(1)
          smtCheck
        }
        assert(result == Seq(Z3Status.Sat, Z3Status.Unsat, Z3Status.Sat))
        assert(calls == 1)
        assert(output.contains("(declare-const a Bool)"))
        assert(output.sliding("(check-sat)".length).count(_ == "(check-sat)") == 3)
        // An independent replay checks the debug artifact, not the execution API.
        val replay          = os.proc("z3", "-in").call(stdin = output, timeout = 10000).out.text().trim.linesIterator.toSeq
        assert(replay == Seq("sat", "unsat", "sat"))
