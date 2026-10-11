// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{CombApi, HWApi}
import org.llvm.circt.scalalib.dialect.comb.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation, TypeApi, Value, given}
import java.lang.foreign.Arena

given CombApi with
  private def result(
    op: Operation
  )(
    using Arena,
    Block
  ): Value =
    op.appendToBlock()
    op.getResult(0)

  def concat(
    inputs: Seq[Value]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Value =
    result(summon[ConcatApi].op(inputs.map(_.asSignless), locate).operation)

  def mux(
    condition: Value,
    whenTrue:  Value,
    whenFalse: Value
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Value =
    result(summon[MuxApi].op(condition, whenTrue, whenFalse, locate).operation)

  extension (input: Value)
    def asSignless(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      val tpe      = input.getType
      val signless = tpe.integerTypeGetWidth.integerTypeGet
      if tpe.equal(signless) then input else summon[HWApi].bitcast(input, signless)

    def extract(
      high: Int,
      low:  Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ExtractApi].op(input.asSignless, low, high - low + 1, locate).operation)

    def zeroExtend(
      width: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      val current = input.getType.integerTypeGetWidth
      require(width >= current, "zero extension cannot truncate")
      if width == current then input.asSignless
      else concat(Seq(summon[HWApi].constant(0, width - current), input))

    infix def +(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[AddApi].op(input.asSignless, rhs.asSignless, locate).operation)

    infix def -(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[SubApi].op(input.asSignless, rhs.asSignless, locate).operation)

    infix def &(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[AndApi].op(input.asSignless, rhs.asSignless, locate).operation)

    infix def |(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[OrApi].op(input.asSignless, rhs.asSignless, locate).operation)

    infix def ^(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[XorApi].op(input.asSignless, rhs.asSignless, locate).operation)

    infix def ===(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ICmpApi].op(ICmpPredicate.Eq, input.asSignless, rhs.asSignless, locate).operation)

    infix def =/=(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ICmpApi].op(ICmpPredicate.Ne, input.asSignless, rhs.asSignless, locate).operation)

    infix def ult(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ICmpApi].op(ICmpPredicate.Ult, input.asSignless, rhs.asSignless, locate).operation)

    infix def ule(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ICmpApi].op(ICmpPredicate.Ule, input.asSignless, rhs.asSignless, locate).operation)

    infix def ugt(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ICmpApi].op(ICmpPredicate.Ugt, input.asSignless, rhs.asSignless, locate).operation)

    infix def uge(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ICmpApi].op(ICmpPredicate.Uge, input.asSignless, rhs.asSignless, locate).operation)

    infix def slt(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ICmpApi].op(ICmpPredicate.Slt, input.asSignless, rhs.asSignless, locate).operation)

    infix def sle(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ICmpApi].op(ICmpPredicate.Sle, input.asSignless, rhs.asSignless, locate).operation)

    infix def sgt(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ICmpApi].op(ICmpPredicate.Sgt, input.asSignless, rhs.asSignless, locate).operation)

    infix def sge(
      rhs: Value
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      result(summon[ICmpApi].op(ICmpPredicate.Sge, input.asSignless, rhs.asSignless, locate).operation)
