// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialect, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{ModuleApi as HWModuleApi, *, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, OperationApi, TypeApi, given}
import utest.*

import java.lang.foreign.Arena

object HWSmoke extends TestSuite:
  val tests: Tests = Tests:
    test("HW dialect"):
      given Arena = Arena.ofAuto()
      given context: Context = summon[ContextApi].contextCreate
      context.allowUnregisteredDialects(true)
      summon[HWDialect].loadDialect
      val unknownLocation = summon[LocationApi].locationUnknownGet
      val scope           = summon[OperationApi].operationCreate(
        name = "test.scope",
        location = unknownLocation,
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)))
      )
      given Block         = scope.getFirstRegion.getFirstBlock

      test("Module"):
        val input  = Port("input", PortDirection.Input, 1.integerTypeGet)
        val output = Port("output", PortDirection.Output, 1.integerTypeGet)
        val module = summon[HWModuleApi].op("Top", Seq(input, output), unknownLocation)
        module.operation.appendToBlock()

        val out = StringBuilder()
        scope.print(out ++= _)
        assert(out.toString().contains("hw.module"))
        assert(module.symbol == "Top")
        assert(
          module.ports.map(port => (port.name, port.direction)) == Seq(
            "input"  -> PortDirection.Input,
            "output" -> PortDirection.Output
          )
        )

      test("ModuleExtern"):
        val i1     = 1.integerTypeGet
        val input  = Port("input", PortDirection.Input, i1)
        val output = Port("output", PortDirection.Output, i1)
        val module = summon[ModuleExternApi].op("Child", Seq(input, output), None, unknownLocation)
        module.operation.appendToBlock()

        val out = StringBuilder()
        scope.print(out ++= _)
        assert(out.toString().contains("hw.module.extern"))

      test("Instance"):
        val instance = summon[InstanceApi].op("child", "Child", Seq.empty, Seq.empty, unknownLocation)
        instance.operation.appendToBlock()

        val out = StringBuilder()
        scope.print(out ++= _)
        assert(out.toString().contains("hw.instance"))

      test("Output"):
        val output = summon[OutputApi].op(Seq.empty, unknownLocation)
        output.operation.appendToBlock()

        val out = StringBuilder()
        scope.print(out ++= _)
        assert(out.toString().contains("hw.output"))
