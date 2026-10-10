// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.SMTQuery
import me.jiuyang.smtlib.default.{*, given}
import utest.*

object SMTQuerySpec extends TestSuite:
  val tests = Tests:
    test("build forwards solver naming context"):
      val query  = SMTQuery.build:
        val core = smtValue(Bool)
        smtAssert(core)
        (0 until 2).foreach { _ => smtAssert(smtValue(Bool)) }
      val replay = query.replay
      assert(replay.contains("(declare-const core Bool)"))
      assert(replay.contains("(assert core)"))
      assert(replay.contains("(declare-const _GEN_0 Bool)"))
      assert(replay.contains("(declare-const _GEN_1 Bool)"))
    test("contextual names remain usable as assumptions"):
      val query  = SMTQuery.build:
        for name <- Seq("rule_clock", "rule_power") do
          given sourcecode.Name.Machine = sourcecode.Name.Machine(name)
          val enabled                   = smtValue(Bool)
          smtAssert(enabled)
      val replay = query.copy(assumptions = Seq("rule_clock", "rule_power")).replay
      assert(replay.contains("(declare-const rule_clock Bool)"))
      assert(replay.contains("(declare-const rule_power Bool)"))
      assert(replay.contains("(check-sat-assuming (|rule_clock| |rule_power|))"))
