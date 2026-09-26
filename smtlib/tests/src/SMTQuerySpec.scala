// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.SMTQuery
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Enumeration, Z3Status}
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

    test("enumeration returns each projected assignment once"):
      val query    = SMTQuery(
        """module {
          |  smt.solver () : () -> () {
          |    %x = smt.declare_fun "x" : !smt.bv<3>
          |    %y = smt.declare_fun "y" : !smt.bv<3>
          |    %low = smt.declare_fun "low" : !smt.bool
          |    %free = smt.declare_fun "free" : !smt.bool
          |    %hidden = smt.declare_fun "hidden" : !smt.bool
          |    %two = smt.bv.constant #smt.bv<2> : !smt.bv<3>
          |    %five = smt.bv.constant #smt.bv<5> : !smt.bv<3>
          |    %ordered = smt.bv.cmp ult %x, %y : !smt.bv<3>
          |    smt.assert %ordered
          |    %sum = smt.bv.add %x, %y : !smt.bv<3>
          |    %total = smt.eq %sum, %five : !smt.bv<3>
          |    smt.assert %total
          |    %small = smt.bv.cmp ult %x, %two : !smt.bv<3>
          |    %flag = smt.eq %low, %small : !smt.bool
          |    smt.assert %flag
          |    smt.yield
          |  }
          |}""".stripMargin
      )
      val expected = for
        x <- 0 until 8
        y <- 0 until 8
        if x < y && (x + y) % 8 == 5
        free <- Seq(false, true)
      yield Map("x" -> BigInt(x), "y" -> BigInt(y), "low" -> (x < 2), "free" -> free)
      val result   = query.enumerate(Seq("x", "y", "low", "free"), 1000)
      assert(result.status == Z3Status.Unsat)
      assert(
        result.models
          .map(_.toMap)
          .sorted(
            using Ordering.by(_.toString)
          ) == expected.sorted(
          using Ordering.by(_.toString)
        )
      )

    test("enumeration of an unsatisfiable query is exhausted without models"):
      val query  = SMTQuery.build:
        val request = smtValue(Bool, "request")
        smtAssert(request & !request)
      val result = query.enumerate(Seq("request"), 1000)
      assert(result.status == Z3Status.Unsat)
      assert(result.models.isEmpty)

    test("resource limit stops a search that finishes without it"):
      val product = BigInt(4292870399L)
      val query   = SMTQuery(
        s"""module {
           |  smt.solver () : () -> () {
           |    %x = smt.declare_fun "x" : !smt.bv<16>
           |    %y = smt.declare_fun "y" : !smt.bv<16>
           |    %zero = smt.bv.constant #smt.bv<0> : !smt.bv<16>
           |    %one = smt.bv.constant #smt.bv<1> : !smt.bv<16>
           |    %product = smt.bv.constant #smt.bv<$product> : !smt.bv<32>
           |    %wide_x = smt.bv.concat %zero, %x : !smt.bv<16>, !smt.bv<16>
           |    %wide_y = smt.bv.concat %zero, %y : !smt.bv<16>, !smt.bv<16>
           |    %mul = smt.bv.mul %wide_x, %wide_y : !smt.bv<32>
           |    %equal = smt.eq %mul, %product : !smt.bv<32>
           |    smt.assert %equal
           |    %x_proper = smt.bv.cmp ugt %x, %one : !smt.bv<16>
           |    smt.assert %x_proper
           |    %y_proper = smt.bv.cmp ugt %y, %one : !smt.bv<16>
           |    smt.assert %y_proper
           |    smt.yield
           |  }
           |}""".stripMargin
      )
      val factors = (2 until 65536).filter(d => product % d == 0 && product / d < 65536).map(BigInt(_))
      assert(query.check(10000, rlimit = 5000).status == Z3Status.Unknown)
      assert(query.enumerate(Seq("x", "y"), 10000, rlimit = 5000) == Z3Enumeration(Z3Status.Unknown, Seq.empty))
      val result  = query.enumerate(Seq("x", "y"), 10000)
      assert(result.status == Z3Status.Unsat)
      assert(result.models.map(_.toMap).toSet == factors.map(x => Map("x" -> x, "y" -> product / x)).toSet)
