// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.default.{locate, valName}
import me.jiuyang.zaozi.sv.{ExpressionApi, TypeImpl}
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.circt.scalalib.dialect.comb.operation.{*, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{EnumCmpApi, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

given ExpressionApi with
  extension (lhs:                              Referable[Bits])
    def +(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[AddApi].op(lhs.refer, rhs.refer, locate)
      node(lhs.getType, op.operation, lhs._bindings)
    def -(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[SubApi].op(lhs.refer, rhs.refer, locate)
      node(lhs.getType, op.operation, lhs._bindings)
    def *(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[MulApi].op(lhs.refer, rhs.refer, locate)
      node(lhs.getType, op.operation, lhs._bindings)
    def &(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[AndApi].op(lhs.refer, rhs.refer, locate)
      node(lhs.getType, op.operation, lhs._bindings)
    def |(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[OrApi].op(lhs.refer, rhs.refer, locate)
      node(lhs.getType, op.operation, lhs._bindings)
    def ^(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[XorApi].op(lhs.refer, rhs.refer, locate)
      node(lhs.getType, op.operation, lhs._bindings)
    def ===(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[ICmpApi].op(lhs.refer, rhs.refer, 0, locate)
      node(Bits(1), op.operation)
    def =/=(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[ICmpApi].op(lhs.refer, rhs.refer, 1, locate)
      node(Bits(1), op.operation)
    def <(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[ICmpApi].op(lhs.refer, rhs.refer, 6, locate)
      node(Bits(1), op.operation)
    def <=(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[ICmpApi].op(lhs.refer, rhs.refer, 7, locate)
      node(Bits(1), op.operation)
    def >(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[ICmpApi].op(lhs.refer, rhs.refer, 8, locate)
      node(Bits(1), op.operation)
    def >=(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[ICmpApi].op(lhs.refer, rhs.refer, 9, locate)
      node(Bits(1), op.operation)
  extension [T <: Enum | TypeAlias[Enum]](lhs: Referable[T])
    @scala.annotation.targetName("enumEqual")
    def ===(
      rhs: Referable[T]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      val op = summon[EnumCmpApi].op(lhs.refer, rhs.refer, locate)
      node(Bits(1), op.operation)
  extension (cond:                             Referable[Bits])
    def ?[T <: Data](
      whenTrue:  Referable[T],
      whenFalse: Referable[T]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[T] =
      val op = summon[MuxApi].op(cond.refer, whenTrue.refer, whenFalse.refer, locate)
      node(whenTrue.getType, op.operation, whenTrue._bindings)

private def node[T <: Data](
  tpe:       T,
  operation: Operation,
  bindings:  Map[String, me.jiuyang.zaozi.sv.ConstExpr] = Map.empty
)(
  using Arena,
  Context,
  Block,
  InstanceContext,
  sourcecode.Name.Machine
): Node[T] =
  operation.setAttributeByName("sv.namehint", valName.stringAttrGet)
  operation.appendToBlock()
  new Node[T]:
    override private[zaozi] val _bindings = bindings
    private[zaozi] val _tpe               = tpe
    private[zaozi] val _refer             = operation.getResult(0)
