// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.utlib

import scala.compiletime.testing.{typeCheckErrors, typeChecks}
import utest.*

object GenTypeTest extends TestSuite:
  val tests: Tests = Tests:
    test("native Bool history is accepted by Gen"):
      val accepted = typeChecks("""
        import me.jiuyang.utlib.Gen
        import me.jiuyang.zaozi.*
        import me.jiuyang.zaozi.default.{*, given}
        import me.jiuyang.zaozi.ltltpe.*
        import me.jiuyang.zaozi.reftpe.*
        import me.jiuyang.zaozi.valuetpe.*
        import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
        import java.lang.foreign.Arena
        def build(signal: Referable[Bits])(using ClockEvent, ClockScope, ResetScope,
          Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
        ): Unit = Gen(past(signal === 5.B(8), 2), "target")
      """)
      assert(accepted)

    test("native history rejects Bits"):
      val errors = typeCheckErrors("""
        import me.jiuyang.utlib.Gen
        import me.jiuyang.zaozi.*
        import me.jiuyang.zaozi.default.{*, given}
        import me.jiuyang.zaozi.ltltpe.*
        import me.jiuyang.zaozi.reftpe.*
        import me.jiuyang.zaozi.valuetpe.*
        import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
        import java.lang.foreign.Arena
        def build(signal: Referable[Bits])(using ClockEvent, ClockScope, ResetScope,
          Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
        ): Referable[Bits] = past(signal, 2)
      """)
      assert(errors.nonEmpty)

    test("Expr accepts hardware Bool, Sequence and Property but not Bits"):
      assert(typeChecks("""
        import me.jiuyang.utlib.Gen
        import me.jiuyang.zaozi.ltltpe.*
        import me.jiuyang.zaozi.reftpe.*
        import me.jiuyang.zaozi.valuetpe.*
        def boolean(p: Referable[Bool]): Gen.Expr = p
        def sequence(p: Sequence): Gen.Expr = p
        def property(p: Property): Gen.Expr = p
      """))
      assert(!typeChecks("""
        import me.jiuyang.utlib.Gen
        import me.jiuyang.zaozi.reftpe.*
        import me.jiuyang.zaozi.valuetpe.*
        def bits(p: Referable[Bits]): Gen.Expr = p
      """))

    test("removed generation APIs are not importable"):
      assert(!typeChecks("import me.jiuyang.utlib.Sem"))
      assert(!typeChecks("import me.jiuyang.utlib.Generate"))
      assert(!typeChecks("import me.jiuyang.utlib.Txn"))
