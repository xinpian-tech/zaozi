// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.SMTQuery
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Status}
import utest.*

object SMTQuerySpec extends TestSuite:
  val tests = Tests:
    test("named assignments survive export and solving"):
      val query  = SMTQuery.build:
        val requests = Seq("domain_core", "domain_memory").map(name => smtValue(Bool, name))
        smtAssert(requests.head)
        smtAssert(!requests.last)
      val result = query.check(1000)
      assert(result.status == Z3Status.Sat)
      assert(result.model.toMap == Map("domain_core" -> true, "domain_memory" -> false))
      val replay = os.proc("z3", "-in").call(stdin = query.replay + "(get-model)\n").out.text()
      assert(parseZ3Output(replay).model.toMap == result.model.toMap)

    test("unsatisfiable queries do not request a model"):
      val query = SMTQuery.build:
        val request = smtValue(Bool, "request")
        smtAssert(request & !request)
      assert(query.check(1000).status == Z3Status.Unsat)

    test("invalid IR is not a proof result"):
      intercept[IllegalArgumentException]:
        SMTQuery("(assert missing)\n").check(1000)

    test("conflicting assumptions retain their source names"):
      val query  = SMTQuery.build:
        val enabled   = smtValue(Bool, "enabled")
        val request   = smtValue(Bool, "request_rule")
        val policy    = smtValue(Bool, "policy_rule")
        val unrelated = smtValue(Bool, "unrelated_rule")
        smtAssert(unrelated ==> true.B)
        smtAssert(request ==> enabled)
        smtAssert(policy ==> !enabled)
      val result = query.copy(assumptions = Seq("request_rule", "policy_rule", "unrelated_rule")).check(1000)
      assert(result.status == Z3Status.Unsat)
      assert(result.conflict.toSet == Set("request_rule", "policy_rule"))
      assert(query.copy(assumptions = Seq("request_rule")).check(1000).status == Z3Status.Sat)

    test("wide values retain their names through native solving and replay"):
      val query  = SMTQuery(
        """module {
          |  smt.solver () : () -> () {
          |    %value = smt.declare_fun "wide_value!7" : !smt.bv<65>
          |    %expected = smt.bv.constant #smt.bv<18446744073709551617> : !smt.bv<65>
          |    %equal = smt.eq %value, %expected : !smt.bv<65>
          |    smt.assert %equal
          |    smt.yield
          |  }
          |}""".stripMargin
      )
      val result = query.check(1000)
      assert(result.model.toMap == Map("wide_value!7" -> BigInt("18446744073709551617")))
      val replay = os.proc("z3", "-in").call(stdin = query.replay + "(get-model)\n").out.text()
      assert(parseZ3Output(replay).model.toMap == result.model.toMap)

    test("unknown remains inconclusive"):
      val query = SMTQuery.build:
        smtSetLogic("HORN")
        val value = smtValue(SInt, "value")
        smtAssert(value === 4.S)
      assert(query.check(1000).status == Z3Status.Unknown)

    test("empty query is satisfiable"):
      val query = SMTQuery.build:
        ()
      assert(query.check(1000).status == Z3Status.Sat)

    test("assumptions must name Boolean declarations"):
      val query = SMTQuery.build:
        smtValue(SInt, "value")
        ()
      intercept[IllegalArgumentException](query.copy(assumptions = Seq("value")).check(1000))
      intercept[IllegalArgumentException](query.copy(assumptions = Seq("missing")).check(1000))

    test("lowering failures are not proof results"):
      val query = SMTQuery.build:
        smtSetLogic("QF_BV")
        smtSetLogic("QF_UF")
        smtAssert(true.B)
      intercept[os.SubprocessException](query.check(1000))
