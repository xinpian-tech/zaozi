// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FirrtlDialect, LinkCircuitsPassApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, ModuleApi, given}
import org.llvm.mlir.scalalib.capi.pass.{PassManagerApi, given}
import org.llvm.mlir.scalalib.capi.support.given
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

  val tests: Tests = Tests:
    test("LinkCircuits pass"):
      given Arena = Arena.ofAuto()
      given context: Context = summon[ContextApi].contextCreate
      summon[FirrtlDialect].loadDialect

      test("selected base with preserved names"):
        val module = summon[ModuleApi].moduleCreateParse(linkInput)
        val pm     = summon[PassManagerApi].passManagerCreate
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
        pm.destroy()
        module.destroy()

      test("default private-name mangling"):
        val module = summon[ModuleApi].moduleCreateParse(linkInput)
        val pm     = summon[PassManagerApi].passManagerCreate
        pm.addOwnedPass(summon[LinkCircuitsPassApi].createLinkCircuitsPass("Other"))
        assert(pm.runOnOp(module.getOperation).succeeded)
        val output = new StringBuilder
        module.getOperation.print(output ++= _)
        assert(output.toString.contains("@Base_Helper"))
        pm.destroy()
        module.destroy()

      test("logical failure"):
        val module = summon[ModuleApi].moduleCreateParse(collidingInput)
        val pm     = summon[PassManagerApi].passManagerCreate
        pm.addOwnedPass(summon[LinkCircuitsPassApi].createLinkCircuitsPass("First", noMangle = true))
        assert(pm.runOnOp(module.getOperation).failed)
        pm.destroy()
        module.destroy()
end LinkCircuitsSmoke
