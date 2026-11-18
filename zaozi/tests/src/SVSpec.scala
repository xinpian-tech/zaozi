// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.SVApi
import me.jiuyang.zaozi.default.given

import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialectApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, ModuleApi, TypeApi, given}

import java.lang.foreign.Arena
import utest.*

object SVSpec extends TestSuite:
  val tests = Tests:
    test("SV"):
      val arena = Arena.ofConfined()
      try
        given Arena   = arena
        given Context = summon[ContextApi].contextCreate
        try
          summon[HWDialectApi].loadDialect
          summon[SVDialectApi].loadDialect

          val container = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
          try
            given Block  = container.getBody
            val register = summon[SVApi].reg(1.integerTypeGet, "value")
            register.readInOut
            summon[SVApi].verbatim("initial {{0}} = 1'b0;", Seq(register))
          finally container.destroy()
        finally summon[Context].destroy()
      finally arena.close()
