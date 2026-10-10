// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.*
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.Z3Status
import utest.*

object NamingSpec extends TestSuite:
  val tests = Tests:
    test("value declarations use Scala val names"):
      val result = smtZ3Test(
        "(declare-const a Bool)",
        "(declare-const b Bool)",
        "(assert a)",
        "(not b)"
      ) {
        val a = smtValue(Bool)
        val b = smtValue(Bool)
        smtAssert(a)
        smtAssert(!b)
      }
      result.status ==> Z3Status.Sat
      result.model.toMap ==> Map("a" -> true, "b" -> false)
    test("function declarations and applications use Scala val names"):
      smtTest(
        "(declare-const a Bool)",
        "(declare-const count Int)",
        "(declare-fun f (Int Bool) Bool)",
        "(declare-fun g (Int Bool) Bool)",
        "(f count a)",
        "(g count a)"
      ):
        solver {
          val a     = smtValue(Bool)
          val count = smtValue(SInt)
          val f     = smtFunc(Seq(SInt, Bool), Bool)
          val g     = smtValue(SMTFunc(Seq(SInt, Bool), Bool))
          smtAssert(f(count, a))
          smtAssert(g(count, a))
        }
    test("declarations in an anonymous function"):
      smtTest("(declare-const _GEN_0 Bool)", "(declare-const _GEN_1 Bool)"):
        solver {
          (0 until 2).foreach { _ => smtAssert(smtValue(Bool)) }
        }
    test("contextual names"):
      smtTest("(declare-const domain_core Bool)", "(declare-fun domain_enable (Int Bool) Bool)"):
        solver {
          val core   = {
            given sourcecode.Name.Machine = sourcecode.Name.Machine("domain_core")
            smtValue(Bool)
          }
          val enable = {
            given sourcecode.Name.Machine = sourcecode.Name.Machine("domain_enable")
            smtFunc(Seq(SInt, Bool), Bool)
          }
          smtAssert(core)
          smtAssert(enable(1.S, core))
        }
    test("anonymous declarations share a counter"):
      smtTest(
        "(declare-const _GEN_0 Bool)",
        "(declare-fun _GEN_1 (Int Bool) Bool)",
        "(declare-const _GEN_2 Int)"
      ):
        solver {
          given sourcecode.Name.Machine = sourcecode.Name.Machine("$anonfun")
          val a                         = smtValue(Bool)
          val b                         = smtFunc(Seq(SInt, Bool), Bool)
          val c                         = smtValue(SInt)
          smtAssert(a)
          smtAssert(b(c, a))
        }
    test("each solver starts a fresh counter"):
      smtTest(
        "; solver scope 0\n(declare-const _GEN_0 Bool)",
        "; solver scope 1\n(declare-const _GEN_0 Bool)"
      ):
        for _ <- 0 until 2 do
          solver {
            given sourcecode.Name.Machine = sourcecode.Name.Machine("$anonfun")
            smtAssert(smtValue(Bool))
          }
    test("export disambiguates explicit and generated names"):
      smtTest("(declare-const _GEN_0 Bool)", "(declare-const _GEN_0_0 Bool)"):
        solver {
          val named     = {
            given sourcecode.Name.Machine = sourcecode.Name.Machine("_GEN_0")
            smtValue(Bool)
          }
          val anonymous = {
            given sourcecode.Name.Machine = sourcecode.Name.Machine("$anonfun")
            smtValue(Bool)
          }
          smtAssert(named)
          smtAssert(anonymous)
        }
