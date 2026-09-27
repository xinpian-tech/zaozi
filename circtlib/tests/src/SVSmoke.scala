// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialect, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialect, given}
import org.llvm.circt.scalalib.dialect.sv.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, OperationApi, TypeApi, given}
import utest.*

import java.lang.foreign.Arena

object SVSmoke extends TestSuite:
  val tests: Tests = Tests:
    test("SV dialect"):
      given Arena = Arena.ofAuto()
      given context: Context = summon[ContextApi].contextCreate
      context.allowUnregisteredDialects(true)
      summon[HWDialect].loadDialect
      summon[SVDialect].loadDialect
      val unknownLocation = summon[LocationApi].locationUnknownGet
      val inout           = 1.integerTypeGet.inOutTypeGet()
      val scope           = summon[OperationApi].operationCreate(
        name = "test.scope",
        location = unknownLocation,
        regionBlockTypeLocations = Seq(Seq((Seq(inout), Seq(unknownLocation))))
      )
      given Block         = scope.getFirstRegion.getFirstBlock

      test("Reg"):
        val register = summon[RegApi].op(1.integerTypeGet, "value", None, unknownLocation)
        register.operation.appendToBlock()

        val out = StringBuilder()
        scope.print(out ++= _)
        assert(out.toString().contains("sv.reg"))

      test("ReadInOut"):
        val value = summon[ReadInOutApi].op(summon[Block].getArgument(0), unknownLocation)
        value.operation.appendToBlock()

        val out = StringBuilder()
        scope.print(out ++= _)
        assert(out.toString().contains("sv.read_inout"))

      test("Verbatim"):
        val verbatim = summon[VerbatimApi].op("initial begin end", Seq.empty, unknownLocation)
        verbatim.operation.appendToBlock()

        val out = StringBuilder()
        scope.print(out ++= _)
        assert(out.toString().contains("sv.verbatim"))
