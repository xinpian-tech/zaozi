// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.{*, given}
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.SInt
import me.jiuyang.smtlib.parser.{
  getUnsatCore,
  handleUnsatResult,
  parseZ3Output,
  parseZ3OutputOrFail,
  Z3Result,
  Z3Status
}

import org.llvm.mlir.scalalib.capi.dialect.func.{Func, FuncApi, given}
import org.llvm.mlir.scalalib.capi.dialect.smt.DialectApi as SmtDialect
import org.llvm.mlir.scalalib.capi.dialect.smt.given_DialectApi
import org.llvm.mlir.scalalib.capi.dialect.func.DialectApi as FuncDialect
import org.llvm.mlir.scalalib.capi.dialect.func.given_DialectApi
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given_ExportSmtlibApi
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, Module, ModuleApi, Value, given}

import java.lang.foreign.Arena

import utest.*

/** Tests for UNSAT scenarios and unsat core generation */
object UnsatTest extends TestSuite:
  val tests = Tests:

    test("SimpleUnsatConstraints"):
      // Test basic UNSAT scenario: x > 5 AND x < 3
      val result = smtZ3UnsatTest {
        val x = smtValue(SInt)
        smtAssert(x > 5.S)
        smtAssert(x < 3.S)
      }

      // Verify that the result is UNSAT
      assert(result.status == Z3Status.Unsat)
      // Verify that model is empty for UNSAT
      assert(result.model.isEmpty)

    test("ConflictingEqualities"):
      // Test UNSAT with conflicting equality constraints
      val result = smtZ3UnsatTest {
        val x = smtValue(SInt)
        smtAssert(x === 5.S)
        smtAssert(x === 10.S)
      }

      assert(result.status == Z3Status.Unsat)
      assert(result.model.isEmpty)

    test("ComplexUnsatConstraints"):
      // Test more complex UNSAT scenario with multiple variables
      val result = smtZ3UnsatTest {
        val x = smtValue(SInt)
        val y = smtValue(SInt)
        smtAssert(x + y === 10.S)
        smtAssert(x > 15.S)
        smtAssert(y > 15.S)
      }

      assert(result.status == Z3Status.Unsat)
      assert(result.model.isEmpty)
