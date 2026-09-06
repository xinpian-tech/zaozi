// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

import utest.*

/** Exercises the formal-UT framework end to end (runs z3): a property that holds proves out to Pass, and a violable
  * property yields Fail with a concrete counterexample.
  */
object FormalUtTest extends TestSuite:
  val tests = Tests:

    test("property proven under the assumptions -> Pass"):
      object T extends FormalUT:
        def name = "x >= 5  implies  x + 1 >= 6"
        def spec(
          using Arena,
          Context,
          Block
        ): Referable[Bool] =
          val x = smtValue("x", SInt)
          smtAssert(x >= 5.S) // assumption
          (x + 1.S) >= 6.S    // property
      assert(FormalUT.check(T) == UtOutcome.Pass)

    test("violable property -> Fail with a counterexample stimulus"):
      object T extends FormalUT:
        def name = "x >= 0  implies  x >= 5  (false)"
        def spec(
          using Arena,
          Context,
          Block
        ): Referable[Bool] =
          val x = smtValue("x", SInt)
          smtAssert(x >= 0.S) // assumption
          x >= 5.S            // property (does not hold)
      FormalUT.check(T) match
        case UtOutcome.Fail(cex) =>
          assert(cex.contains("x"))
          // the counterexample satisfies the assumption but violates the property
          assert(cex("x") >= 0 && cex("x") < 5)
        case other               => assert(false)
