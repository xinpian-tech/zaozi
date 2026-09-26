// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.*
import utest.*

import me.jiuyang.smtlib.parser.Z3Status

object TypeSpec extends TestSuite:
  val tests = Tests:
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
    test("Array"):
      test("declare"):
        withSMTContext:
          solver {
            val a = smtValue(Array(SInt, Bool))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("select"):
        withSMTContext:
          solver {
            val a = smtValue(Array(SInt, Bool))
            smtAssert(a(0))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("store"):
        withSMTContext:
          solver {
            val a = smtValue(Array(SInt, Bool))
            val b = a.update(0, true.B)
            smtAssert(b(0))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("broadcast"):
        withSMTContext:
          solver {
            val a = smtValue(Array(SInt, Bool))
            val b = a.broadcast(true.B)
            smtAssert(b(10))
            smtCheck
          } ==> Seq(Z3Status.Sat)
    test("BitVector"):
      test("declare"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("==="):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            smtAssert(a === 0.B(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("=/="):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            smtAssert(a =/= 0.B(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("+>>"):
        test("Int"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(true, 32))
              smtAssert(a +>> 1 === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)
        test("SInt"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(true, 32))
              smtAssert(a +>> 1.B(true, 32) === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)
      test(">>"):
        test("Int"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(true, 32))
              smtAssert(a >> 1 === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)
        test("SInt"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(true, 32))
              smtAssert(a >> 1.B(true, 32) === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)
      test("<<"):
        test("Int"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(true, 32))
              smtAssert(a << 1 === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)
        test("SInt"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(true, 32))
              smtAssert(a << 1.B(true, 32) === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)
      test("+"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            val b = smtValue(BitVector(true, 32))
            smtAssert(a + b === 0.B(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("*"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            val b = smtValue(BitVector(true, 32))
            smtAssert(a * b === 0.B(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("/"):
        test("sdiv"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(true, 32))
              val b = smtValue(BitVector(true, 32))
              smtAssert(a / b === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)
        test("udiv"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(false, 32))
              val b = smtValue(BitVector(true, 32))
              smtAssert(a / b === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)
      test("%"):
        test("srem"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(true, 32))
              val b = smtValue(BitVector(true, 32))
              smtAssert(a % b === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)
        test("urem"):
          withSMTContext:
            solver {
              val a = smtValue(BitVector(false, 32))
              val b = smtValue(BitVector(true, 32))
              smtAssert(a % b === 0.B(true, 32))
              smtCheck
            } ==> Seq(Z3Status.Sat)
      test("&"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            val b = smtValue(BitVector(true, 32))
            smtAssert((a & b) === 0.B(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("|"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            val b = smtValue(BitVector(true, 32))
            smtAssert((a | b) === 0.B(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("^"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            val b = smtValue(BitVector(true, 32))
            smtAssert((a ^ b) === 0.B(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("++"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            val b = smtValue(BitVector(true, 32))
            smtAssert((a ++ b) === 0.B(true, 64))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("extract"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            smtAssert(a.extract(0, BitVector(true, 1)) === 0.B(true, 1))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("repeat"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 4))
            smtAssert(a.repeat(BitVector(true, 32)) === 0.B(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("~"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            smtAssert(~a === 0.B(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("!"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            smtAssert(!a === 0.B(true, 32))
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("<"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            val b = smtValue(BitVector(true, 32))
            smtAssert(a < b)
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("<="):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            val b = smtValue(BitVector(true, 32))
            smtAssert(a <= b)
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test(">"):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            val b = smtValue(BitVector(true, 32))
            smtAssert(a > b)
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test(">="):
        withSMTContext:
          solver {
            val a = smtValue(BitVector(true, 32))
            val b = smtValue(BitVector(true, 32))
            smtAssert(a >= b)
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
      test("/"):
        withSMTContext:
          solver {
            val a = smtValue(SInt)
            val b = smtValue(SInt)
            smtAssert(a / b === 0.S)
            smtCheck
          } ==> Seq(Z3Status.Sat)
      test("%"):
        withSMTContext:
          solver {
            val a = smtValue(SInt)
            val b = smtValue(SInt)
            smtAssert(a % b === 0.S)
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
    test("Sort"):
      test("declare"):
        withSMTContext:
          solver {
            val a = smtValue(Sort("b", Seq(SInt)))
            smtCheck
          } ==> Seq(Z3Status.Sat)
