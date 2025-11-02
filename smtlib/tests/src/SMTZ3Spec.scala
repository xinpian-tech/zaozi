// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.*
import me.jiuyang.smtlib.tpe.*
import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Status}
import utest.*

import java.lang.foreign.Arena

object SMTZ3Spec extends TestSuite:
  val tests = Tests:
    test("Z3 output"):
      test("sat"):
        val parsed = smtZ3Test(
          "(declare-const x Bool)",
          "(assert x)",
          "(check-sat)"
        ) {
          val x = smtValue(Bool)
          smtAssert(x)
        }.status ==> Z3Status.Sat

      test("unsat"):
        smtZ3Test(
          "(declare-const x Bool)",
          "(assert (let ((tmp (not x)))",
          "        (let ((tmp_0 (and x tmp)))",
          "        tmp_0)))",
          "(check-sat)"
        ) {
          val x = smtValue(Bool)
          smtAssert(x & !x)
        }.status ==> Z3Status.Unsat

      test("unknown"):
        // reference from: https://stackoverflow.com/questions/66797639/z3py-extremely-slow-with-many-variables
        smtZ3Test(
          "(declare-const x1 Int)",
          "(declare-const x2 Int)",
          "(declare-const x3 Int)",
          "(declare-const x4 Int)",
          "(declare-const x5 Int)",
          "(declare-const x6 Int)",
          "(declare-const x7 Int)",
          "(declare-const x8 Int)",
          "(declare-const x9 Int)",
          "(declare-const x10 Int)",
          "(declare-const x11 Int)",
          "(declare-const x12 Int)",
          "(declare-const x13 Int)",
          "(declare-const x14 Int)",
          "(declare-const x15 Int)",
          "(declare-const x16 Int)",
          "(assert (let ((tmp (<= x16 10)))",
          "        (let ((tmp_0 (>= x16 0)))",
          "        (let ((tmp_1 (<= x15 10)))",
          "        (let ((tmp_2 (>= x15 0)))",
          "        (let ((tmp_3 (<= x14 10)))",
          "        (let ((tmp_4 (>= x14 0)))",
          "        (let ((tmp_5 (<= x13 10)))",
          "        (let ((tmp_6 (>= x13 0)))",
          "        (let ((tmp_7 (<= x12 10)))",
          "        (let ((tmp_8 (>= x12 0)))",
          "        (let ((tmp_9 (<= x11 10)))",
          "        (let ((tmp_10 (>= x11 0)))",
          "        (let ((tmp_11 (<= x10 10)))",
          "        (let ((tmp_12 (>= x10 0)))",
          "        (let ((tmp_13 (<= x9 10)))",
          "        (let ((tmp_14 (>= x9 0)))",
          "        (let ((tmp_15 (<= x8 10)))",
          "        (let ((tmp_16 (>= x8 0)))",
          "        (let ((tmp_17 (<= x7 10)))",
          "        (let ((tmp_18 (>= x7 0)))",
          "        (let ((tmp_19 (<= x6 10)))",
          "        (let ((tmp_20 (>= x6 0)))",
          "        (let ((tmp_21 (<= x5 10)))",
          "        (let ((tmp_22 (>= x5 0)))",
          "        (let ((tmp_23 (<= x4 10)))",
          "        (let ((tmp_24 (>= x4 0)))",
          "        (let ((tmp_25 (<= x3 10)))",
          "        (let ((tmp_26 (>= x3 0)))",
          "        (let ((tmp_27 (<= x2 10)))",
          "        (let ((tmp_28 (>= x2 0)))",
          "        (let ((tmp_29 (<= x1 10)))",
          "        (let ((tmp_30 (>= x1 0)))",
          "        (let ((tmp_31 (and tmp_30 tmp_29)))",
          "        (let ((tmp_32 (and tmp_31 tmp_28)))",
          "        (let ((tmp_33 (and tmp_32 tmp_27)))",
          "        (let ((tmp_34 (and tmp_33 tmp_26)))",
          "        (let ((tmp_35 (and tmp_34 tmp_25)))",
          "        (let ((tmp_36 (and tmp_35 tmp_24)))",
          "        (let ((tmp_37 (and tmp_36 tmp_23)))",
          "        (let ((tmp_38 (and tmp_37 tmp_22)))",
          "        (let ((tmp_39 (and tmp_38 tmp_21)))",
          "        (let ((tmp_40 (and tmp_39 tmp_20)))",
          "        (let ((tmp_41 (and tmp_40 tmp_19)))",
          "        (let ((tmp_42 (and tmp_41 tmp_18)))",
          "        (let ((tmp_43 (and tmp_42 tmp_17)))",
          "        (let ((tmp_44 (and tmp_43 tmp_16)))",
          "        (let ((tmp_45 (and tmp_44 tmp_15)))",
          "        (let ((tmp_46 (and tmp_45 tmp_14)))",
          "        (let ((tmp_47 (and tmp_46 tmp_13)))",
          "        (let ((tmp_48 (and tmp_47 tmp_12)))",
          "        (let ((tmp_49 (and tmp_48 tmp_11)))",
          "        (let ((tmp_50 (and tmp_49 tmp_10)))",
          "        (let ((tmp_51 (and tmp_50 tmp_9)))",
          "        (let ((tmp_52 (and tmp_51 tmp_8)))",
          "        (let ((tmp_53 (and tmp_52 tmp_7)))",
          "        (let ((tmp_54 (and tmp_53 tmp_6)))",
          "        (let ((tmp_55 (and tmp_54 tmp_5)))",
          "        (let ((tmp_56 (and tmp_55 tmp_4)))",
          "        (let ((tmp_57 (and tmp_56 tmp_3)))",
          "        (let ((tmp_58 (and tmp_57 tmp_2)))",
          "        (let ((tmp_59 (and tmp_58 tmp_1)))",
          "        (let ((tmp_60 (and tmp_59 tmp_0)))",
          "        (let ((tmp_61 (and tmp_60 tmp)))",
          "        tmp_61))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))",
          "(assert (let ((tmp_62 (distinct x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16)))",
          "        tmp_62))",
          "(check-sat)"
        ) {
          val x1  = smtValue(SInt)
          val x2  = smtValue(SInt)
          val x3  = smtValue(SInt)
          val x4  = smtValue(SInt)
          val x5  = smtValue(SInt)
          val x6  = smtValue(SInt)
          val x7  = smtValue(SInt)
          val x8  = smtValue(SInt)
          val x9  = smtValue(SInt)
          val x10 = smtValue(SInt)
          val x11 = smtValue(SInt)
          val x12 = smtValue(SInt)
          val x13 = smtValue(SInt)
          val x14 = smtValue(SInt)
          val x15 = smtValue(SInt)
          val x16 = smtValue(SInt)
          smtAssert(
            x1 >= 0.S & x1 <= 10.S
              & x2 >= 0.S & x2 <= 10.S
              & x3 >= 0.S & x3 <= 10.S
              & x4 >= 0.S & x4 <= 10.S
              & x5 >= 0.S & x5 <= 10.S
              & x6 >= 0.S & x6 <= 10.S
              & x7 >= 0.S & x7 <= 10.S
              & x8 >= 0.S & x8 <= 10.S
              & x9 >= 0.S & x9 <= 10.S
              & x10 >= 0.S & x10 <= 10.S
              & x11 >= 0.S & x11 <= 10.S
              & x12 >= 0.S & x12 <= 10.S
              & x13 >= 0.S & x13 <= 10.S
              & x14 >= 0.S & x14 <= 10.S
              & x15 >= 0.S & x15 <= 10.S
              & x16 >= 0.S & x16 <= 10.S
          )
          // format: off
          smtAssert(smtDistinct(x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15, x16))
          // format: on
        }.status ==> Z3Status.Unknown

    test("CoreSpec"):
      test("assert"):
        test("Const[Bool]"):
          smtZ3Test("assert true") {
            smtAssert(true.B)
          }.status ==> Z3Status.Sat

          smtZ3Test("assert false") {
            smtAssert(false.B)
          }.status ==> Z3Status.Unsat

        test("Ref[Bool]"):
          smtZ3Test("declare-const a Bool") {
            val a = smtValue(Bool)
            smtAssert(a)
          }.model ==> Map("a" -> true)

      test("declare"):
        test("const"):
          smtZ3Test("declare-const a Bool") {
            val a = smtValue(Bool)
            smtAssert(a)
          }.model ==> Map("a" -> true)

        test("fun"):
          smtZ3Test("declare-fun a (Int Bool) Bool") {
            val a = smtFunc(Seq(SInt, Bool), Bool)
            smtAssert(a(1.S, true.B))
          }.model ==> Map("a" -> true)

      test("distinct"):
        test("Const[Bool]"):
          smtZ3Test("distinct true") {
            smtAssert(smtDistinct(true.B))
          }.status ==> Z3Status.Sat

          smtZ3Test("distinct true true") {
            smtAssert(smtDistinct(true.B, true.B))
          }.status ==> Z3Status.Unsat

          smtZ3Test("distinct true false") {
            smtAssert(smtDistinct(true.B, false.B))
          }.status ==> Z3Status.Sat

        test("Ref[Bool]"):
          smtZ3Test(
            "(declare-const a Bool)",
            "(declare-const b Bool)",
            "(assert (let ((tmp (distinct a b)))",
            "        tmp))"
          ) {
            val a = smtValue(Bool)
            val b = smtValue(Bool)
            smtAssert(smtDistinct(a, b))
          }.status ==> Z3Status.Sat

      test("set-logic"):
        smtZ3Test("(set-logic QF_LIA)") {
          smtSetLogic("QF_LIA")
        }.status ==> Z3Status.Sat

      test("eq"):
        smtZ3Test("= true true") {
          smtAssert(smtEq(true.B, true.B))
        }.status ==> Z3Status.Sat

        smtZ3Test("= true false") {
          smtAssert(smtEq(true.B, false.B))
        }.status ==> Z3Status.Unsat

        smtZ3Test(
          "(declare-const a Bool)",
          "(declare-const b Int)",
          "(assert (let ((tmp (= a b)))",
          "        tmp))"
        ) {
          val a = smtValue(Bool)
          val b = smtValue(SInt)
          smtAssert(smtEq(a, b))
        }.status ==> Z3Status.Sat

      test("ite"):
        smtZ3Test("ite true true false") {
          val cond = true.B
          smtAssert(smtIte(true.B) { true.B } { false.B })
        }.status ==> Z3Status.Sat

        smtZ3Test(
          "(declare-const a Bool)",
          "(declare-const b Int)",
          "(declare-const c Int)",
          "(assert (let ((tmp (ite a b c)))",
          "        (let ((tmp_0 (= tmp 0)))",
          "        tmp_0)))"
        ) {
          val a = smtValue(Bool)
          val b = smtValue(SInt)
          val c = smtValue(SInt)
          smtAssert(smtIte(a) { b } { c } === 0.S)
        }.status ==> Z3Status.Sat

      test("push"):
        smtZ3Test("push 1") {
          smtPush(1)
        }.status ==> Z3Status.Sat

      test("pop"):
        smtZ3Test("pop 1") {
          smtPush(1)
          smtPop(1)
        }.status ==> Z3Status.Sat

      test("yield"):
        smtZ3Test("") {
          smtYield()
        }.status ==> Z3Status.Sat

    test("TypeSpec"):
      test("Const"):
        test("BitVector"):
          smtZ3Test(
            "(declare-const a (_ BitVec 32))",
            "(assert (let ((tmp (= a #x00000000)))",
            "        tmp))"
          ) {
            val a = smtValue(BitVector(true, 32))
            smtAssert(a === 0.B(true, 32))
          }.status ==> Z3Status.Sat

        test("Bool"):
          smtZ3Test(
            "(assert true)"
          ) {
            smtAssert(true.B)
          }.status ==> Z3Status.Sat

          smtZ3Test(
            "(assert false)"
          ) {
            smtAssert(false.B)
          }.status ==> Z3Status.Unsat

          smtZ3Test(
            "(declare-const a Bool)",
            "(assert (let ((tmp (= a true)))",
            "        tmp))"
          ) {
            val a = smtValue(Bool)
            smtAssert(a === true.B)
          }.status ==> Z3Status.Sat

          smtZ3Test(
            "(declare-const a Bool)",
            "(assert (let ((tmp (distinct a true)))",
            "        tmp))"
          ) {
            val a = smtValue(Bool)
            smtAssert(a =/= true.B)
          }.status ==> Z3Status.Sat

        test("Int"):
          smtZ3Test(
            "(declare-const a Int)",
            "(assert (let ((tmp (= a 1)))",
            "        tmp))"
          ) {
            val a = smtValue(SInt)
            smtAssert(a === 1.S)
          }.status ==> Z3Status.Sat

          smtZ3Test(
            "(declare-const a Int)",
            "(assert (let ((tmp (= a 0)))",
            "        tmp))"
          ) {
            val a = smtValue(SInt)
            smtAssert(a === 0.S)
          }.status ==> Z3Status.Sat

      test("Bool"):
        test("declare"):
          smtZ3Test(
            "(declare-const a Bool)"
          ) {
            val a = smtValue(Bool)
          }.status ==> Z3Status.Sat

        test("&"):
          smtZ3Test(
            "(declare-const a Bool)",
            "(declare-const b Bool)",
            "(assert (let ((tmp (and a b)))",
            "        tmp))"
          ) {
            val a = smtValue(Bool)
            val b = smtValue(Bool)
            smtAssert(a & b)
          }.status ==> Z3Status.Sat

        test("|"):
          smtZ3Test(
            "(declare-const a Bool)",
            "(declare-const b Bool)",
            "(assert (let ((tmp (or a b)))",
            "        tmp))"
          ) {
            val a = smtValue(Bool)
            val b = smtValue(Bool)
            smtAssert(a | b)
          }.status ==> Z3Status.Sat

        test("!"):
          smtZ3Test(
            "(declare-const a Bool)",
            "(assert (let ((tmp (not a)))",
            "        tmp))"
          ) {
            val a = smtValue(Bool)
            smtAssert(!a)
          }.status ==> Z3Status.Sat

        test("^"):
          smtZ3Test(
            "(declare-const a Bool)",
            "(declare-const b Bool)",
            "(assert (let ((tmp (xor a b)))",
            "        tmp))"
          ) {
            val a = smtValue(Bool)
            val b = smtValue(Bool)
            smtAssert(a ^ b)
          }.status ==> Z3Status.Sat

        test("==>"):
          smtZ3Test(
            "(declare-const a Bool)",
            "(declare-const b Bool)",
            "(assert (let ((tmp (=> a b)))",
            "        tmp))"
          ) {
            val a = smtValue(Bool)
            val b = smtValue(Bool)
            smtAssert(a ==> b)
          }.status ==> Z3Status.Sat

      test("Int"):
        test("declare"):
          smtZ3Test(
            "(declare-const a Int)"
          ) {
            val a = smtValue(SInt)
          }.status ==> Z3Status.Sat

        test("==="):
          smtZ3Test(
            "(declare-const a Int)",
            "(assert (let ((tmp (= a 0)))",
            "        tmp))"
          ) {
            val a = smtValue(SInt)
            smtAssert(a === 0.S)
          }.status ==> Z3Status.Sat

        test("=/="):
          smtZ3Test(
            "(declare-const a Int)",
            "(assert (let ((tmp (distinct a 0)))",
            "        tmp))"
          ) {
            val a = smtValue(SInt)
            smtAssert(a =/= 0.S)
          }.status ==> Z3Status.Sat

        test("+"):
          smtZ3Test(
            "(declare-const a Int)",
            "(declare-const b Int)",
            "(assert (let ((tmp (+ a b)))",
            "        (let ((tmp_0 (= tmp 0)))",
            "        tmp_0)))"
          ) {
            val a = smtValue(SInt)
            val b = smtValue(SInt)
            smtAssert(a + b === 0.S)
          }.status ==> Z3Status.Sat

        test("-"):
          smtZ3Test(
            "(declare-const a Int)",
            "(declare-const b Int)",
            "(assert (let ((tmp (- a b)))",
            "        (let ((tmp_0 (= tmp 0)))",
            "        tmp_0)))"
          ) {
            val a = smtValue(SInt)
            val b = smtValue(SInt)
            smtAssert(a - b === 0.S)
          }.status ==> Z3Status.Sat

        test("*"):
          smtZ3Test(
            "(declare-const a Int)",
            "(declare-const b Int)",
            "(assert (let ((tmp (* a b)))",
            "        (let ((tmp_0 (= tmp 0)))",
            "        tmp_0)))"
          ) {
            val a = smtValue(SInt)
            val b = smtValue(SInt)
            smtAssert(a * b === 0.S)
          }.status ==> Z3Status.Sat

        test(">"):
          smtZ3Test(
            "(declare-const a Int)",
            "(declare-const b Int)",
            "(assert (let ((tmp (> a b)))",
            "        tmp))"
          ) {
            val a = smtValue(SInt)
            val b = smtValue(SInt)
            smtAssert(a > b)
          }.status ==> Z3Status.Sat

        test(">="):
          smtZ3Test(
            "(declare-const a Int)",
            "(declare-const b Int)",
            "(assert (let ((tmp (>= a b)))",
            "        tmp))"
          ) {
            val a = smtValue(SInt)
            val b = smtValue(SInt)
            smtAssert(a >= b)
          }.status ==> Z3Status.Sat

        test("<"):
          smtZ3Test(
            "(declare-const a Int)",
            "(declare-const b Int)",
            "(assert (let ((tmp (< a b)))",
            "        tmp))"
          ) {
            val a = smtValue(SInt)
            val b = smtValue(SInt)
            smtAssert(a < b)
          }.status ==> Z3Status.Sat

        test("<="):
          smtZ3Test(
            "(declare-const a Int)",
            "(declare-const b Int)",
            "(assert (let ((tmp (<= a b)))",
            "        tmp))"
          ) {
            val a = smtValue(SInt)
            val b = smtValue(SInt)
            smtAssert(a <= b)
          }.status ==> Z3Status.Sat

      test("SMTFunc"):
        test("declare"):
          smtZ3Test(
            "(declare-fun f (Int Bool) Bool)"
          ) {
            val f = smtValue(SMTFunc(Seq(SInt, Bool), Bool))
          }.status ==> Z3Status.Sat

        test("apply"):
          smtZ3Test(
            "(declare-fun f (Int Bool) Bool)",
            "(assert (let ((tmp (f 0 true)))",
            "        tmp))"
          ) {
            val f = smtValue(SMTFunc(Seq(SInt, Bool), Bool))
            val a = f(0.S, true.B)
            smtAssert(a)
          }.status ==> Z3Status.Sat
