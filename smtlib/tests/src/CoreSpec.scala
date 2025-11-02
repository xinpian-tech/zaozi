// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.*
import utest.*

import java.lang.foreign.Arena

object CoreSpec extends TestSuite:
  val tests = Tests:
    test("assert"):
      test("Const[Bool]"):
        smtTest("assert true"):
          solver {
            smtAssert(true.B)
          }
        smtTest("assert false"):
          solver {
            smtAssert(false.B)
          }
      test("Ref[Bool]"):
        smtTest("declare-const a Bool"):
          solver {
            val a = smtValue(Bool)
            smtAssert(a)
          }
    test("declare"):
      test("const"):
        smtTest("declare-const a Bool"):
          solver {
            val a = smtValue(Bool)
            smtAssert(a)
          }
      test("fun"):
        smtTest("declare-fun a (Int Bool) Bool"):
          solver {
            val a = smtFunc(Seq(SInt, Bool), Bool)
            smtAssert(a(1.S, true.B))
          }
    test("and"):
      test("Const[Bool]"):
        smtTest("and"):
          solver {
            smtAssert(smtAnd())
          }
        smtTest("and true"):
          solver {
            smtAssert(smtAnd(true.B))
          }
        smtTest("and true true"):
          solver {
            smtAssert(smtAnd(true.B, true.B))
          }
        smtTest("and true false"):
          solver {
            smtAssert(smtAnd(true.B, false.B))
          }
        smtTest("and true true false false"):
          solver {
            smtAssert(smtAnd(true.B, true.B, false.B, false.B))
          }
      test("Ref[Bool]"):
        smtTest(
          "(declare-const a Bool)",
          "(declare-const b Bool)",
          "(assert (let ((tmp (and a b)))",
          "        tmp))"
        ):
          solver {
            val a = smtValue(Bool)
            val b = smtValue(Bool)
            smtAssert(smtAnd(a, b))
          }
    test("or"):
      test("Const[Bool]"):
        smtTest("or"):
          solver {
            smtAssert(smtOr())
          }
        smtTest("or true"):
          solver {
            smtAssert(smtOr(true.B))
          }
        smtTest("or true true"):
          solver {
            smtAssert(smtOr(true.B, true.B))
          }
        smtTest("or true false"):
          solver {
            smtAssert(smtOr(true.B, false.B))
          }
        smtTest("or true true false false"):
          solver {
            smtAssert(smtOr(true.B, true.B, false.B, false.B))
          }
      test("Ref[Bool]"):
        smtTest(
          "(declare-const a Bool)",
          "(declare-const b Bool)",
          "(assert (let ((tmp (or a b)))",
          "        tmp))"
        ):
          solver {
            val a = smtValue(Bool)
            val b = smtValue(Bool)
            smtAssert(smtOr(a, b))
          }
    test("distinct"):
      test("Const[Bool]"):
        smtTest("distinct"):
          solver {
            smtAssert(smtDistinct())
          }
        smtTest("distinct true"):
          solver {
            smtAssert(smtDistinct(true.B))
          }
        smtTest("distinct true true"):
          solver {
            smtAssert(smtDistinct(true.B, true.B))
          }
        smtTest("distinct true false"):
          solver {
            smtAssert(smtDistinct(true.B, false.B))
          }
        smtTest("distinct true true false false"):
          solver {
            smtAssert(smtDistinct(true.B, true.B, false.B, false.B))
          }
      test("Ref[Bool]"):
        smtTest(
          "(declare-const a Bool)",
          "(declare-const b Bool)",
          "(assert (let ((tmp (distinct a b)))",
          "        tmp))"
        ):
          solver {
            val a = smtValue(Bool)
            val b = smtValue(Bool)
            smtAssert(smtDistinct(a, b))
          }
    test("reset"):
      smtTest("(reset)\n(reset)"):
        solver {
          smtReset
        }
    test("set-logic"):
      smtTest("(set-logic QF_LIA)"):
        solver {
          smtSetLogic("QF_LIA")
        }
      smtTest("(set-logic XXX)"):
        solver {
          smtSetLogic("XXX")
        }
    test("eq"):
      smtTest("="):
        solver {
          smtAssert(smtEq())
        }
      smtTest("= true"):
        solver {
          smtAssert(smtEq(true.B))
        }
      smtTest("= true true"):
        solver {
          smtAssert(smtEq(true.B, true.B))
        }
      smtTest("= true false"):
        solver {
          smtAssert(smtEq(true.B, false.B))
        }
      smtTest(
        "(declare-const a Bool)",
        "(declare-const b Int)",
        "(assert (let ((tmp (= a b)))",
        "        tmp))"
      ):
        solver {
          val a = smtValue(Bool)
          val b = smtValue(SInt)
          smtAssert(smtEq(a, b))
        }
      smtTest("= true 1"):
        solver {
          smtAssert(smtEq(true.B, 1.S))
        }
      smtTest(
        "(declare-sort e 1)",
        "(declare-const a Bool)",
        "(declare-const b Int)",
        "(declare-const c (_ BitVec 32))",
        "(declare-fun d (Int Bool) Bool)",
        "(declare-const e (e Int))",
        "(assert (let ((tmp (= true 1 #x00000001 a b c d e)))",
        "        tmp))"
      ):
        solver {
          val a = smtValue(Bool)
          val b = smtValue(SInt)
          val c = smtValue(BitVector(true, 32))
          val d = smtValue(SMTFunc(Seq(SInt, Bool), Bool))
          val e = smtValue(Sort("e", Seq(SInt)))
          smtAssert(smtEq(true.B, 1.S, 1.B(true, 32), a, b, c, d, e))
        }
    test("ite"):
      smtTest("ite true true false"):
        solver {
          val cond = true.B
          smtAssert(smtIte(true.B) { true.B } { false.B })
        }
      smtTest(
        "(declare-const a Bool)",
        "(declare-const b Int)",
        "(declare-const c Int)",
        "(assert (let ((tmp (ite a b c)))",
        "        (let ((tmp_0 (= tmp 0)))",
        "        tmp_0)))"
      ):
        solver {
          val a = smtValue(Bool)
          val b = smtValue(SInt)
          val c = smtValue(SInt)
          smtAssert(smtIte(a) { b } { c } === 0.S)
        }
    test("push"):
      smtTest("push 1"):
        solver {
          smtPush(1)
        }
    test("pop"):
      smtTest("pop 1"):
        solver {
          smtPop(1)
        }
    test("check"):
      smtTest("(check-sat)"):
        solver {
          smtCheck
        }
    test("yield"):
      smtTest(""):
        solver {
          smtYield()
        }
