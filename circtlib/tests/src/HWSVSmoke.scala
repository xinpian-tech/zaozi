// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialect, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialect, given}
import org.llvm.circt.scalalib.capi.exportverilog.given_ExportVerilogApi
import org.llvm.circt.scalalib.dialect.hw.operation.{ModuleApi as HWModuleApi, *, given}
import org.llvm.circt.scalalib.dialect.sv.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, LocationApi, ModuleApi as MlirModuleApi, TypeApi, given}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi
import utest.*

import java.lang.foreign.Arena

object HWSVSmoke extends TestSuite:
  val tests: Tests = Tests:
    test("build and export an HW/SV module"):
      val arena = Arena.ofConfined()
      try
        given Arena   = arena
        given Context = summon[ContextApi].contextCreate
        try
          summon[HWDialect].loadDialect
          summon[SVDialect].loadDialect

          val location = summon[LocationApi].locationUnknownGet
          val i1       = 1.integerTypeGet
          val input    = Port("input", PortDirection.Input, i1)
          val output   = Port("output", PortDirection.Output, i1)
          val module   = summon[MlirModuleApi].moduleCreateEmpty(location)
          try
            val external = summon[ModuleExternApi].op("Child", Seq(input), None, location)
            external.operation.appendToBlock()(using module.getBody)

            val top = summon[HWModuleApi].op("Top", Seq(output), location)
            top.operation.appendToBlock()(using module.getBody)
            given org.llvm.mlir.scalalib.capi.ir.Block = top.block

            val register = summon[RegApi].op(i1, "value", None, location)
            register.operation.appendToBlock()
            val value = summon[ReadInOutApi].op(register.result, location)
            value.operation.appendToBlock()
            summon[VerbatimApi].op("initial {{0}} = 1'b0;", Seq(register.result), location).operation.appendToBlock()
            summon[InstanceApi].op("child", "Child", Seq(input), Seq(value.result), location).operation.appendToBlock()
            summon[OutputApi].op(Seq(value.result), location).operation.appendToBlock()

            assert(module.getOperation.verify)
            val verilog = new StringBuilder
            assert(module.exportVerilog(verilog.append(_)).succeeded)
            val source = verilog.toString
            assert(source.contains("module Top"))
            assert(source.contains("Child child"))
            assert(!source.contains("module Child"))
          finally module.destroy()
        finally summon[Context].destroy()
      finally arena.close()
