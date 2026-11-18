// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.zaozi.mlirlib.tests

import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import scala.scalanative.unsafe.*
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.executionengine.{*, given}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import utest.*

object ExecutionEngineSpec extends TestSuite:
  private def run(): Unit =
    val zone = Zone.open()
    try
      given Zone   = zone
      val registry = summon[DialectRegistryApi].registryCreate()
      mlirApi.mlirRegisterAllDialects(registry.native)
      val context  = summon[ContextApi].contextCreateWithRegistry(registry, false)
      registry.destroy()
      try
        given Context = context
        // This is already lowered IR: exercise the C ABI's argument and result
        // storage independently of any CIRCT conversion pass.
        val module    = summon[ModuleApi].moduleCreateParse("""
          module {
            llvm.func @_mlir_ciface_add(%a: i32, %b: i32) -> i32 {
              %sum = llvm.add %a, %b : i32
              llvm.return %sum : i32
            }
          }
        """)
        val engine    =
          try summon[ExecutionEngineApi].executionEngineCreate(module)
          finally module.destroy()
        assert(!engine.isNull)
        try
          engine.initialize()
          val a      = alloc[Int]()
          val b      = alloc[Int]()
          val result = alloc[Int]()
          for (x, y) <- Seq((19, 23), (-8, 3)) do
            !a = x
            !b = y
            assert(engine.invokePacked("add", Seq(a, b, result)).succeeded)
            assert(!result == x + y)
          assert(engine.lookup("_mlir_ciface_add") != null)
          assert(engine.lookupPacked("_mlir_ciface_add") != null)
          assert(engine.lookupPacked("missing") == null)
          assert(!engine.invokePacked("missing", Seq.empty).succeeded)
        finally engine.destroy()
      finally context.destroy()
    finally zone.close()

  val tests = Tests:
    test("packed arguments and result survive module destruction"):
      run()
