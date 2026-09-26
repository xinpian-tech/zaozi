// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.*
import me.jiuyang.smtlib.tpe.*
import me.jiuyang.smtlib.parser.Z3Status
import utest.*

object SMTZ3Spec extends TestSuite:
  val tests = Tests:
    test("native results"):
      test("sat"):
        withSMTContext:
          solver {
            val x = smtValue(Bool)
            smtAssert(x)
            smtCheck
          } ==> Seq(Z3Status.Sat)

      test("unsat"):
        withSMTContext:
          solver {
            val x = smtValue(Bool)
            smtAssert(x & !x)
            smtCheck
          } ==> Seq(Z3Status.Unsat)

      test("bounded distinct is unsatisfiable"):
        withSMTContext:
          solver {
            val values = Seq.fill(4)(smtValue(SInt))
            values.foreach(x => smtAssert((x >= 0.S) & (x <= 2.S)))
            smtAssert(smtDistinct(values*))
            smtCheck
          } ==> Seq(Z3Status.Unsat)

    test("CoreSpec"):
      test("assert"):
        test("Const[Bool]"):
          withSMTContext:
            solver {
              smtAssert(true.B)
              smtCheck
            } ==> Seq(Z3Status.Sat)

          withSMTContext:
            solver {
              smtAssert(false.B)
              smtCheck
            } ==> Seq(Z3Status.Unsat)

        test("Ref[Bool]"):
          withSMTContext:
            solver {
              val a = smtValue(Bool)
              smtAssert(a)
              smtCheck
              smtAssert(!a)
              smtCheck
            } ==> Seq(Z3Status.Sat, Z3Status.Unsat)

      test("declare"):
        test("const"):
          withSMTContext:
            solver {
              val a = smtValue(Bool)
              smtAssert(a)
              smtCheck
              smtAssert(!a)
              smtCheck
            } ==> Seq(Z3Status.Sat, Z3Status.Unsat)

        test("fun"):
          withSMTContext:
            solver {
              val a = smtFunc(Seq(SInt, Bool), Bool)
              smtAssert(a(1.S, true.B))
              smtCheck
              smtAssert(!a(1.S, true.B))
              smtCheck
            } ==> Seq(Z3Status.Sat, Z3Status.Unsat)

      test("distinct"):
        test("Const[Bool]"):
          withSMTContext:
            solver {
              smtAssert(smtDistinct(true.B, false.B))
              smtCheck
            } ==> Seq(Z3Status.Sat)

          withSMTContext:
            solver {
              smtAssert(smtDistinct(true.B, true.B))
              smtCheck
            } ==> Seq(Z3Status.Unsat)

          withSMTContext:
            solver {
              smtAssert(smtDistinct(true.B, false.B))
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("Ref[Bool]"):
          withSMTContext:
            solver {
              val a = smtValue(Bool)
              val b = smtValue(Bool)
              smtAssert(smtDistinct(a, b))
              smtCheck
            } ==> Seq(Z3Status.Sat)

      test("set-logic"):
        withSMTContext:
          solver {
            smtSetLogic("QF_LIA")
            smtCheck
          } ==> Seq(Z3Status.Sat)

      test("eq"):
        withSMTContext:
          solver {
            smtAssert(smtEq(true.B, true.B))
            smtCheck
          } ==> Seq(Z3Status.Sat)

        withSMTContext:
          solver {
            smtAssert(smtEq(true.B, false.B))
            smtCheck
          } ==> Seq(Z3Status.Unsat)

        withSMTContext:
          solver {
            val a = smtValue(SInt)
            val b = smtValue(SInt)
            smtAssert(smtEq(a, b))
            smtCheck
          } ==> Seq(Z3Status.Sat)

      test("ite"):
        withSMTContext:
          solver {
            val cond = true.B
            smtAssert(smtIte(true.B) { true.B } { false.B })
            smtCheck
          } ==> Seq(Z3Status.Sat)

        withSMTContext:
          solver {
            val a = smtValue(Bool)
            val b = smtValue(SInt)
            val c = smtValue(SInt)
            smtAssert(smtIte(a) { b } { c } === 0.S)
            smtCheck
          } ==> Seq(Z3Status.Sat)

      test("push"):
        withSMTContext:
          solver {
            smtPush(1)
            smtCheck
          } ==> Seq(Z3Status.Sat)

      test("pop"):
        withSMTContext:
          solver {
            smtPush(1)
            smtPop(1)
            smtCheck
          } ==> Seq(Z3Status.Sat)

      test("yield"):
        withSMTContext:
          solver {
            smtAssert(true.B)
            smtCheck
          } ==> Seq(Z3Status.Sat)

    test("TypeSpec"):
      test("Const"):
        test("BitVector"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(true, 32))
              smtAssert(a === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("Bool"):
          withSMTContext:
            solver {
              smtAssert(true.B)
              smtCheck
            } ==> Seq(Z3Status.Sat)

          withSMTContext:
            solver {
              smtAssert(false.B)
              smtCheck
            } ==> Seq(Z3Status.Unsat)

          withSMTContext:
            solver {
              val a = smtValue(Bool)
              smtAssert(a === true.B)
              smtCheck
            } ==> Seq(Z3Status.Sat)

          withSMTContext:
            solver {
              val a = smtValue(Bool)
              smtAssert(a =/= true.B)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("Int"):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              smtAssert(a === 1.S)
              smtCheck
            } ==> Seq(Z3Status.Sat)

          withSMTContext:
            solver {
              val a = smtValue(SInt)
              smtAssert(a === 0.S)
              smtCheck
            } ==> Seq(Z3Status.Sat)

      test("Bool"):
        test("declare"):
          withSMTContext:
            solver {
              val a = smtValue(Bool)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("&"):
          withSMTContext:
            solver {
              val a = smtValue(Bool)
              val b = smtValue(Bool)
              smtAssert(a & b)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("|"):
          withSMTContext:
            solver {
              val a = smtValue(Bool)
              val b = smtValue(Bool)
              smtAssert(a | b)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("!"):
          withSMTContext:
            solver {
              val a = smtValue(Bool)
              smtAssert(!a)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("^"):
          withSMTContext:
            solver {
              val a = smtValue(Bool)
              val b = smtValue(Bool)
              smtAssert(a ^ b)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("==>"):
          withSMTContext:
            solver {
              val a = smtValue(Bool)
              val b = smtValue(Bool)
              smtAssert(a ==> b)
              smtCheck
            } ==> Seq(Z3Status.Sat)

      test("Int"):
        test("declare"):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("==="):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              smtAssert(a === 0.S)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("=/="):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              smtAssert(a =/= 0.S)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("+"):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              val b = smtValue(SInt)
              smtAssert(a + b === 0.S)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("-"):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              val b = smtValue(SInt)
              smtAssert(a - b === 0.S)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("*"):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              val b = smtValue(SInt)
              smtAssert(a * b === 0.S)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test(">"):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              val b = smtValue(SInt)
              smtAssert(a > b)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test(">="):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              val b = smtValue(SInt)
              smtAssert(a >= b)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("<"):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              val b = smtValue(SInt)
              smtAssert(a < b)
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("<="):
          withSMTContext:
            solver {
              val a = smtValue(SInt)
              val b = smtValue(SInt)
              smtAssert(a <= b)
              smtCheck
            } ==> Seq(Z3Status.Sat)

      test("SMTFunc"):
        test("declare"):
          withSMTContext:
            solver {
              val f = smtValue(SMTFunc(Seq(SInt, Bool), Bool))
              smtCheck
            } ==> Seq(Z3Status.Sat)

        test("apply"):
          withSMTContext:
            solver {
              val f = smtValue(SMTFunc(Seq(SInt, Bool), Bool))
              val a = f(0.S, true.B)
              smtAssert(a)
              smtCheck
            } ==> Seq(Z3Status.Sat)

    test("Sudoku"):
      withSMTContext:
        solver {
          smtSetLogic("QF_LIA")
          val f1 = smtValue(SInt)
          val f2 = smtValue(SInt)
          val f3 = smtValue(SInt)
          val f4 = smtValue(SInt)
          val f5 = smtValue(SInt)
          val f6 = smtValue(SInt)
          val f7 = smtValue(SInt)
          val f8 = smtValue(SInt)
          val f9 = smtValue(SInt)
          val s  = smtValue(SInt)

          smtAssert(
            f1 >= 1.S & f1 <= 9.S & f2 >= 1.S & f2 <= 9.S & f3 >= 1.S & f3 <= 9.S & f4 >= 1.S & f4 <= 9.S &
              f5 >= 1.S & f5 <= 9.S & f6 >= 1.S & f6 <= 9.S & f7 >= 1.S & f7 <= 9.S & f8 >= 1.S & f8 <= 9.S &
              f9 >= 1.S & f9 <= 9.S & smtDistinct(f1, f2, f3, f4, f5, f6, f7, f8, f9)
          )

          smtAssert(s === f1 + f2 + f3)
          smtAssert(s === f4 + f5 + f6)
          smtAssert(s === f7 + f8 + f9)
          smtAssert(s === f1 + f4 + f7)
          smtAssert(s === f2 + f5 + f8)
          smtAssert(s === f3 + f6 + f9)
          smtAssert(s === f1 + f5 + f9)
          smtAssert(s === f3 + f5 + f7)

          smtCheck
          // The original nine-cell constraints force every row sum to 15.
          smtPush(1)
          smtAssert(s =/= 15.S)
          smtCheck
          smtPop(1)
          smtCheck
        } ==> Seq(Z3Status.Sat, Z3Status.Unsat, Z3Status.Sat)
