// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.mlirlib.tests

import org.llvm.mlir.MlirOperation
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import utest.*

import java.lang.foreign.Arena

object SymbolTableSmoke extends TestSuite:
  val tests: Tests = Tests:
    test("Symbol table"):
      given Arena = Arena.ofAuto()
      given context: Context = summon[ContextApi].contextCreate
      val module      = summon[ModuleApi].moduleCreateParse(
        "module { module @Top {} module @Nested { module @Hidden {} } }"
      )
      val symbolTable = summon[SymbolTableApi].symbolTableCreate(module.getOperation)

      test("Lookup"):
        val operation = symbolTable.lookup("Top")
        val expected  = module.getBody.getFirstOperation
        assert(MlirOperation.ptr(operation.segment).address != 0)
        assert(MlirOperation.ptr(operation.segment).address == MlirOperation.ptr(expected.segment).address)
        symbolTable.destroy()
        assert(operation.getName.str == "builtin.module")
        assert(module.getOperation.verify)
        module.destroy()
        context.destroy()

      test("Missing symbol"):
        assert(MlirOperation.ptr(symbolTable.lookup("Missing").segment).address == 0)
        assert(MlirOperation.ptr(symbolTable.lookup("Hidden").segment).address == 0)
        symbolTable.destroy()
        module.destroy()
        context.destroy()
