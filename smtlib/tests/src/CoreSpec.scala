// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.Z3Status
import utest.*

object CoreSpec extends TestSuite:
  val tests = Tests:
    test("assert"):
      withSMTContext:
        solver { smtAssert(true.B); smtCheck } ==> Seq(Z3Status.Sat)
        solver { smtAssert(false.B); smtCheck } ==> Seq(Z3Status.Unsat)
        solver {
          val a = smtValue(Bool)
          smtAssert(a)
          smtCheck
          smtAssert(!a)
          smtCheck
        } ==> Seq(Z3Status.Sat, Z3Status.Unsat)
    test("function application"):
      withSMTContext:
        solver {
          val f = smtFunc(Seq(SInt, Bool), Bool)
          smtAssert(f(1.S, true.B))
          smtCheck
          smtAssert(!f(1.S, true.B))
          smtCheck
        } ==> Seq(Z3Status.Sat, Z3Status.Unsat)
    test("distinct"):
      withSMTContext:
        solver { smtAssert(smtDistinct(true.B, true.B)); smtCheck } ==> Seq(Z3Status.Unsat)
        solver { smtAssert(smtDistinct(true.B, false.B)); smtCheck } ==> Seq(Z3Status.Sat)
        solver { smtAssert(smtDistinct(true.B, true.B, false.B, false.B)); smtCheck } ==> Seq(Z3Status.Unsat)
        solver {
          val a = smtValue(Bool)
          val b = smtValue(Bool)
          smtAssert(smtDistinct(a, b))
          smtCheck
          smtAssert(a === b)
          smtCheck
        } ==> Seq(Z3Status.Sat, Z3Status.Unsat)
    test("reset"):
      withSMTContext:
        solver {
          smtAssert(false.B)
          smtCheck
          smtReset
          smtCheck
        } ==> Seq(Z3Status.Unsat, Z3Status.Sat)
    test("set-logic"):
      withSMTContext:
        solver {
          smtSetLogic("QF_LIA")
          val a = smtValue(SInt)
          smtAssert(a > 0.S)
          smtCheck
        } ==> Seq(Z3Status.Sat)
    test("eq"):
      withSMTContext:
        solver { smtAssert(smtEq(true.B, true.B)); smtCheck } ==> Seq(Z3Status.Sat)
        solver { smtAssert(smtEq(true.B, false.B)); smtCheck } ==> Seq(Z3Status.Unsat)
        solver {
          val a = smtValue(SInt)
          val b = smtValue(SInt)
          smtAssert(smtEq(a, b, 1.S))
          smtCheck
          smtAssert(a =/= 1.S)
          smtCheck
        } ==> Seq(Z3Status.Sat, Z3Status.Unsat)
    test("invalid equality and distinct operands are rejected"):
      withSMTContext:
        intercept[IllegalArgumentException]:
          solver { smtAssert(smtEq()); smtCheck }
        intercept[IllegalArgumentException]:
          solver { smtAssert(smtEq(true.B)); smtCheck }
        intercept[IllegalArgumentException]:
          solver { smtAssert(smtEq(true.B, 1.S)); smtCheck }
        intercept[IllegalArgumentException]:
          solver { smtAssert(smtDistinct()); smtCheck }
        intercept[IllegalArgumentException]:
          solver { smtAssert(smtDistinct(true.B)); smtCheck }
    test("ite"):
      withSMTContext:
        solver { smtAssert(smtIte(true.B) { true.B } { false.B }); smtCheck } ==> Seq(Z3Status.Sat)
        solver {
          val a = smtValue(Bool)
          val b = smtValue(SInt)
          val c = smtValue(SInt)
          smtAssert(b === 1.S)
          smtAssert(c === 2.S)
          smtAssert(smtIte(a) { b } { c } === 0.S)
          smtCheck
        } ==> Seq(Z3Status.Unsat)
    test("push and pop"):
      withSMTContext:
        solver {
          val a = smtValue(Bool)
          smtAssert(a)
          smtCheck
          smtPush(1)
          smtAssert(!a)
          smtCheck
          smtPop(1)
          smtCheck
        } ==> Seq(Z3Status.Sat, Z3Status.Unsat, Z3Status.Sat)
    test("check"):
      withSMTContext:
        solver { smtCheck; smtCheck } ==> Seq(Z3Status.Sat, Z3Status.Sat)
