// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.HWApi
import me.jiuyang.zaozi.default.given

import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{Port, PortDirection}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, ModuleApi, TypeApi, given}

import java.lang.foreign.Arena
import utest.*

object HWSpec extends TestSuite:
  val tests = Tests:
    test("HW"):
      val arena = Arena.ofConfined()
      try
        given Arena   = arena
        given Context = summon[ContextApi].contextCreate
        try
          summon[HWDialectApi].loadDialect

          val location  = summon[LocationApi].locationUnknownGet
          val i1        = 1.integerTypeGet
          val input     = Port("input", PortDirection.Input, i1)
          val output    = Port("output", PortDirection.Output, i1)
          val container = summon[ModuleApi].moduleCreateEmpty(location)
          try
            given Block = container.getBody
            summon[HWApi].moduleExtern("Child", Seq(input, output))
            summon[HWApi].module("Top", Seq(input, output)):
              val result = summon[HWApi]
                .instance("child", "Child", Seq(input, output), Seq(summon[Block].getArgument(0)))
              summon[HWApi].output(result)

            assert(container.getOperation.verify)
          finally container.destroy()
        finally summon[Context].destroy()
      finally arena.close()
