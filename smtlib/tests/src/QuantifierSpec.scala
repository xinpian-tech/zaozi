// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.Z3Status
import utest.*

object QuantifierSpec extends TestSuite:
  val tests = Tests:
    test("Exist"):
      withSMTContext:
        solver {
          smtAssert(smtExists(1, false, Seq.empty) {
            val x = smtValue(SInt)
            smtYield(x === 1.S)
          })
          smtCheck
        } ==> Seq(Z3Status.Sat)
    test("Forall"):
      withSMTContext:
        solver {
          smtAssert(smtForall(1, false, Seq.empty) {
            val x = smtValue(SInt)
            smtYield(x === 1.S)
          })
          smtCheck
        } ==> Seq(Z3Status.Unsat)
    test("Forall identity"):
      withSMTContext:
        solver {
          smtAssert(smtForall(1, false, Seq("bound")) {
            val x = smtValue(SInt)
            smtYield(x === x)
          })
          smtCheck
        } ==> Seq(Z3Status.Sat)
    test("empty binders are rejected before native execution"):
      withSMTContext:
        intercept[IllegalArgumentException]:
          solver {
            smtAssert(smtExists(1, false, Seq.empty) { smtYield(true.B) })
            smtCheck
          }
