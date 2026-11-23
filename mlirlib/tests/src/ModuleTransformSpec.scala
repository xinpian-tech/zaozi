// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.zaozi.mlirlib.tests

import org.llvm.mlir.scalalib.{Mlir, ModuleTransform}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import utest.*

object ModuleTransformSpec extends TestSuite:
  val tests: Tests = Tests:
    test("a visitor can use distinct operations after the walk returns"):
      Mlir.withContext {
        val module = summon[ModuleApi].moduleCreateParse("module { module @first {} module @second {} }")
        try
          val operations = collection.mutable.ArrayBuffer.empty[Operation]
          module.getOperation.walk(op => { operations += op; WalkResultEnum.Advance }, WalkEnum.PreOrder)
          val names      = operations.drop(1).map(_.getAttributeByName("sym_name").stringAttrGetValue)
          assert(names.toList == List("first", "second"))
        finally module.destroy()
      }

    test("independent transform invocations exchange their modified module as bytecode"):
      val original = Mlir.withContext {
        val module = summon[ModuleApi].moduleCreateParse("module {}")
        try Mlir.bytecode(module)
        finally module.destroy()
      }
      val write    = ModuleTransform(()) { module =>
        module.getOperation.setDiscardableAttributeByName("test.payload", "a\u0000b".stringAttrGet)
      }
      val read     = ModuleTransform(()) { module =>
        val payload = module.getOperation.getDiscardableAttributeByName("test.payload").stringAttrGetValue
        assert(payload == "a\u0000b")
      }
      val output   = read(write(original))
      assert(output.contains(0.toByte))
      intercept[IllegalArgumentException](read(Array[Byte](1, 2, 3)))
      intercept[IllegalArgumentException](read(original.take(8)))
