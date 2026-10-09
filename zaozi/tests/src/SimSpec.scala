// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.{DpiArg, HWApi, SVApi}
import me.jiuyang.zaozi.default.given
import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.seq.{DialectApi as SeqDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialectApi, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{Port, PortDirection}
import org.llvm.circt.scalalib.dialect.sim.operation.DPIDirection
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, ModuleApi, TypeApi, given}

import java.lang.foreign.Arena
import utest.*

object SimSpec extends TestSuite:
  val tests = Tests:
    test("ordered DPI procedure"):
      val arena = Arena.ofConfined()
      try
        given Arena   = arena
        given Context = summon[ContextApi].contextCreate
        try
          summon[HWDialectApi].loadDialect
          summon[SeqDialectApi].loadDialect
          summon[SVDialectApi].loadDialect
          val module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
          try
            given Block = module.getBody
            val first   = summon[SVApi].dpiFunction("first", None, Seq(DpiArg("value", DPIDirection.Return, 8)))
            val second  = summon[SVApi].dpiFunction("second", None, Seq(DpiArg("value", DPIDirection.In, 8)))
            summon[HWApi].module(
              "SimTop",
              Seq(
                Port("clock", PortDirection.Input, 1.integerTypeGet),
                Port("enable", PortDirection.Input, 1.integerTypeGet)
              )
            ):
              val clock    = summon[Block].getArgument(0).toClock
              val enable   = summon[Block].getArgument(1)
              val register = summon[SVApi].reg(8.integerTypeGet, "value")
              summon[SVApi].onClock(clock, Some(enable)):
                val value = summon[SVApi].dpiCallProcedural(first)("value")
                assert(value.getType.equal(8.integerTypeGet))
                summon[SVApi].dpiCallProcedural(second, Seq(value))
                summon[SVApi].nonBlockingAssign(register, value)
              summon[HWApi].output(Seq.empty)
            assert(module.getOperation.verify)
          finally module.destroy()
        finally summon[Context].destroy()
      finally arena.close()
