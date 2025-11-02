// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.parser.{parseSExpr, parseSMT, parseZ3Output, Z3Result, Z3Status}
import me.jiuyang.smtlib.parser.SMTCommand.*
import utest.*

object ParseSpec extends TestSuite:
  val tests = Tests:
    test("simple parser"):
      parseSExpr(
        """(set-logic QF_UF)
          |(declare-fun x () Int)
          |(assert (> x 5))
          |(check-sat)
          |""".stripMargin
      ).isSuccess ==> true
    test("convert to SMT IR"):
      parseSMT(
        """(set-logic QF_UF)
          |(declare-fun x () Int)
          |(assert (> x 5))
          |(check-sat)
          |""".stripMargin
      ) ==> List(
        SetLogic("QF_UF"),
        DeclareFun("x", List(), "Int"),
        Assert(IntCmp(">", Variable("x"), IntConstant(5))),
        Check
      )
    test("parse SMT Output"):
      parseSMT(
        """(
          |   (define-fun x () Bool true)
          |   (define-fun y () Bool false)
          |)""".stripMargin.stripPrefix("(").stripSuffix(")")
      ) ==> List(
        DefineFun("x", List(), "Bool", BoolConstant(true)),
        DefineFun("y", List(), "Bool", BoolConstant(false))
      )
    test("parse Z3 Result"):
      parseZ3Output(
        """sat
          |(
          |  (define-fun x () Bool
          |    true)
          |  (define-fun y () Bool
          |    false)
          |  (define-fun z () Int
          |    1)
          |)""".stripMargin
      ) ==> Z3Result(
        Z3Status.Sat,
        Map(
          "x" -> true,
          "y" -> false,
          "z" -> BigInt(1)
        )
      )
