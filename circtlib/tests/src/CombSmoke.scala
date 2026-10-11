// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.comb.{DialectApi as CombDialect, given}
import org.llvm.circt.scalalib.dialect.comb.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, OperationApi, TypeApi, given}
import java.lang.foreign.Arena
import utest.*

object CombSmoke extends TestSuite:
  val tests: Tests = Tests:
    test("Comb dialect"):
      given Arena = Arena.ofAuto()
      given context: Context = summon[ContextApi].contextCreate
      context.allowUnregisteredDialects(true)
      summon[CombDialect].loadDialect
      val location = summon[LocationApi].locationUnknownGet
      val scope    = summon[OperationApi].operationCreate(
        name = "test.scope",
        location = location,
        regionBlockTypeLocations =
          Seq(Seq((Seq(8.integerTypeGet, 8.integerTypeGet, 1.integerTypeGet), Seq.fill(3)(location))))
      )
      given Block  = scope.getFirstRegion.getFirstBlock
      val a        = summon[Block].getArgument(0)
      val b        = summon[Block].getArgument(1)
      val cond     = summon[Block].getArgument(2)

      test("Add"):
        val op = summon[AddApi].op(a, b, location).operation
        op.appendToBlock()
        assert(op.getName.str == "comb.add")

      test("Sub"):
        val op = summon[SubApi].op(a, b, location).operation
        op.appendToBlock()
        assert(op.getName.str == "comb.sub")

      test("And"):
        val op = summon[AndApi].op(a, b, location).operation
        op.appendToBlock()
        assert(op.getName.str == "comb.and")

      test("Or"):
        val op = summon[OrApi].op(a, b, location).operation
        op.appendToBlock()
        assert(op.getName.str == "comb.or")

      test("Xor"):
        val op = summon[XorApi].op(a, b, location).operation
        op.appendToBlock()
        assert(op.getName.str == "comb.xor")

      test("ICmp"):
        val op = summon[ICmpApi].op(ICmpPredicate.Eq, a, b, location).operation
        op.appendToBlock()
        assert(op.getName.str == "comb.icmp")

      test("Concat"):
        val op = summon[ConcatApi].op(Seq(a, b), location).operation
        op.appendToBlock()
        assert(op.getName.str == "comb.concat")

      test("Extract"):
        val op = summon[ExtractApi].op(a, 2, 4, location).operation
        op.appendToBlock()
        assert(op.getName.str == "comb.extract")

      test("Mux"):
        val op = summon[MuxApi].op(cond, a, b, location).operation
        op.appendToBlock()
        assert(op.getName.str == "comb.mux")
