// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FirrtlDialect, LinkCircuitsPassApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, ModuleApi, given}
import org.llvm.mlir.scalalib.capi.pass.{PassManagerApi, given}
import utest.*

import java.lang.foreign.Arena

object LinkCircuitsSmoke extends TestSuite:
  private val linkInput =
    """module {
      |  firrtl.circuit "Base" {
      |    firrtl.module @Base() {}
      |    firrtl.module private @Helper() {}
      |  }
      |  firrtl.circuit "Other" {
      |    firrtl.module @Other() {}
      |  }
      |} """.stripMargin

  private val collidingInput =
    """module {
      |  firrtl.circuit "First" {
      |    firrtl.module @First() {}
      |    firrtl.module @Shared() {}
      |  }
      |  firrtl.circuit "Second" {
      |    firrtl.module @Second() {}
      |    firrtl.module @Shared(in %in: !firrtl.uint<1>) {}
      |  }
      |} """.stripMargin

  private var currentArena:   Arena   = null
  private var currentContext: Context = null

  override def utestBeforeEach(path: Seq[String]): Unit =
    currentArena = Arena.ofConfined()
    currentContext = null

  override def utestAfterEach(path: Seq[String]): Unit =
    val context = currentContext
    val arena   = currentArena
    currentContext = null
    currentArena = null
    try if context != null then context.destroy()
    finally if arena != null then arena.close()

  val tests: Tests = Tests:
    test("link circuits with selected base and preserved names"):
      given Arena   = currentArena
      val context   = summon[ContextApi].contextCreate
      currentContext = context
      given Context = context
      summon[FirrtlDialect].loadDialect

      val module = summon[ModuleApi].moduleCreateParse(linkInput)
      val pm     = summon[PassManagerApi].passManagerCreate
      try
        pm.addOwnedPass(summon[LinkCircuitsPassApi].createLinkCircuitsPass("Other", noMangle = true))
        assert(pm.runOnOp(module.getOperation).succeeded)
        val output = new StringBuilder
        module.getOperation.print(output ++= _)
        val ir     = output.toString
        assert(ir.split("firrtl.circuit", -1).length == 2)
        assert(ir.contains("firrtl.circuit \"Other\""))
        assert(ir.contains("@Base"))
        assert(ir.contains("@Other"))
        assert(ir.contains("@Helper"))
        assert(!ir.contains("@Base_Helper"))
      finally
        pm.destroy()
        module.destroy()

    test("default linking mangles private module names"):
      given Arena   = currentArena
      val context   = summon[ContextApi].contextCreate
      currentContext = context
      given Context = context
      summon[FirrtlDialect].loadDialect

      val module = summon[ModuleApi].moduleCreateParse(linkInput)
      val pm     = summon[PassManagerApi].passManagerCreate
      try
        pm.addOwnedPass(summon[LinkCircuitsPassApi].createLinkCircuitsPass("Other"))
        assert(pm.runOnOp(module.getOperation).succeeded)
        val output = new StringBuilder
        module.getOperation.print(output ++= _)
        assert(output.toString.contains("@Base_Helper"))
      finally
        pm.destroy()
        module.destroy()

    test("link failure returns an MLIR logical failure"):
      given Arena   = currentArena
      val context   = summon[ContextApi].contextCreate
      currentContext = context
      given Context = context
      summon[FirrtlDialect].loadDialect

      val module = summon[ModuleApi].moduleCreateParse(collidingInput)
      val pm     = summon[PassManagerApi].passManagerCreate
      try
        pm.addOwnedPass(summon[LinkCircuitsPassApi].createLinkCircuitsPass("First", noMangle = true))
        assert(pm.runOnOp(module.getOperation).failed)
      finally
        pm.destroy()
        module.destroy()
end LinkCircuitsSmoke
