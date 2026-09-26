// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.zaozi.mlirlib.tests

import java.lang.foreign.{Arena, ValueLayout}
import org.llvm.mlir.CAPI
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.executionengine.{*, given}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import utest.*

object ExecutionEngineSpec extends TestSuite:
  private def run(): Unit =
    val arena = Arena.ofConfined()
    try
      given Arena  = arena
      val registry = summon[DialectRegistryApi].registryCreate()
      CAPI.mlirRegisterAllDialects(registry.segment)
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
          val a      = arena.allocate(ValueLayout.JAVA_INT)
          val b      = arena.allocate(ValueLayout.JAVA_INT)
          val result = arena.allocate(ValueLayout.JAVA_INT)
          for (x, y) <- Seq((19, 23), (-8, 3)) do
            a.set(ValueLayout.JAVA_INT, 0, x)
            b.set(ValueLayout.JAVA_INT, 0, y)
            assert(engine.invokePacked("add", Seq(a, b, result)).succeeded)
            assert(result.get(ValueLayout.JAVA_INT, 0) == x + y)
          assert(engine.lookup("_mlir_ciface_add").address() != 0)
          assert(engine.lookupPacked("_mlir_ciface_add").address() != 0)
          assert(engine.lookupPacked("missing").address() == 0)
          assert(!engine.invokePacked("missing", Seq.empty).succeeded)
        finally engine.destroy()
      finally context.destroy()
    finally arena.close()

  val tests = Tests:
    test("packed arguments and result survive module destruction"):
      run()
