// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.{HWApi, SeqApi}
import me.jiuyang.zaozi.default.given

import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.seq.{DialectApi as SeqDialectApi, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{Port, PortDirection}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, ModuleApi, TypeApi, given}

import java.lang.foreign.Arena
import utest.*

object SeqSpec extends TestSuite:
  val tests = Tests:
    test("Seq"):
      val arena = Arena.ofConfined()
      try
        given Arena   = arena
        given Context = summon[ContextApi].contextCreate
        try
          summon[HWDialectApi].loadDialect
          summon[SeqDialectApi].loadDialect

          val container = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
          try
            given Block = container.getBody
            summon[HWApi].module("SeqTop", Seq(Port("input", PortDirection.Input, 1.integerTypeGet))):
              val api      = summon[SeqApi]
              val clock    = api.toClock(summon[Block].getArgument(0))
              val inverted = api.clockInv(clock)
              assert(api.clockType.isClock)
              assert(clock.getType.isClock)
              assert(inverted.getType.isClock)
              summon[HWApi].output(Seq.empty)

            assert(container.getOperation.verify)
          finally container.destroy()
        finally summon[Context].destroy()
      finally arena.close()
