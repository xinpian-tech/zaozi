// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.{CombApi, HWApi, SVApi, SVCase}
import me.jiuyang.zaozi.default.given
import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.comb.{DialectApi as CombDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialectApi, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{Port, PortDirection}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, ModuleApi, TypeApi, given}
import java.lang.foreign.Arena
import utest.*

object CombSpec extends TestSuite:
  val tests = Tests:
    test("integer expressions and procedural state"):
      val arena     = Arena.ofConfined()
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      try
        summon[HWDialectApi].loadDialect
        summon[CombDialectApi].loadDialect
        summon[SVDialectApi].loadDialect
        val module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
        try
          given Block = module.getBody
          val hw      = summon[HWApi]
          val sv      = summon[SVApi]
          val comb    = summon[CombApi]
          hw.module(
            "CombTop",
            Seq(Port("a", PortDirection.Input, 8.integerTypeGet), Port("b", PortDirection.Input, 8.integerTypeGet))
          ):
            val a        = summon[Block].getArgument(0)
            val b        = summon[Block].getArgument(1)
            Seq(a + b, a - b, a & b, a | b, a ^ b).foreach(value => assert(value.getType.equal(8.integerTypeGet)))
            Seq(a === b, a =/= b, a.ult(b), a.ule(b), a.ugt(b), a.uge(b), a.slt(b), a.sle(b), a.sgt(b), a.sge(b))
              .foreach(value => assert(value.getType.equal(1.integerTypeGet)))
            assert(comb.mux(a === b, a, b).getType.equal(8.integerTypeGet))
            assert(a.extract(5, 2).getType.equal(4.integerTypeGet))
            assert(a.zeroExtend(128).getType.equal(128.integerTypeGet))
            assert(comb.concat(Seq(a, b)).getType.equal(16.integerTypeGet))
            val large    = hw.constant(BigInt("fedcba98765432100123456789abcdef", 16), 128)
            val negative = hw.constant(-1, 128)
            val register = sv.reg(128.integerTypeGet, "state")
            intercept[IllegalArgumentException](hw.constant(256, 8))
            intercept[IllegalArgumentException](a.extract(8, 0))
            intercept[IllegalArgumentException](a.zeroExtend(7))
            intercept[IllegalArgumentException](comb.concat(Seq.empty))
            intercept[IllegalArgumentException](sv.switch(a, Seq(SVCase(0, ()), SVCase(0, ())))())
            sv.initial:
              sv.blockingAssign(register, large)
              sv.ifElse(a === b) {
                sv.blockingAssign(register, negative)
              } {
                sv.switch(
                  register.readInOut,
                  Seq(
                    SVCase(
                      BigInt("fedcba98765432100123456789abcdef", 16), {
                        sv.nonBlockingAssign(register, large)
                      }
                    )
                  )
                ) {
                  sv.nonBlockingAssign(register, negative)
                }
              }
            hw.output(Seq.empty)
          assert(module.getOperation.verify)
        finally module.destroy()
      finally
        summon[Context].destroy()
        arena.close()
