// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.{BoolApi, InstanceContext}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_FirrtlBundleFieldApi, given_TypeApi, FirrtlNameKind}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  AndPrimApi,
  AsClockPrimApi,
  EQPrimApi,
  MuxPrimApi,
  NEQPrimApi,
  NodeApi,
  NotPrimApi,
  OrPrimApi,
  XorPrimApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, Operation, Value, given}

import java.lang.foreign.Arena

given BoolApi with
  extension [LHS <: Referable[Bool]](ref: LHS)
    def asClock(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[LHS, Clock] =
      val asClockOp = summon[AsClockPrimApi].op(ref.refer, locate)
      asClockOp.operation.appendToBlock()
      val nodeOp    = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = asClockOp.result
      )
      nodeOp.operation.appendToBlock()
      propagate[LHS, Clock](ref, new Object with Clock, nodeOp.operation.getResult(0))

    def asReset(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[LHS, Reset] =
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = ref.refer
      )
      nodeOp.operation.appendToBlock()
      propagate[LHS, Reset](ref, new Object with Reset, nodeOp.operation.getResult(0))

    def unary_!(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] =
      val op0    = summon[NotPrimApi].op(ref.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bool]:
        val _tpe:   Bool  = new Object with Bool
        val _refer: Value = nodeOp.operation.getResult(0)

    def asBits(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[LHS, Bits] =
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = ref.refer
      )
      nodeOp.operation.appendToBlock()
      val tpe    = new Object with Bits:
        private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
      propagate[LHS, Bits](ref, tpe, nodeOp.operation.getResult(0))

    def ===[RHS <: Referable[Bool]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] =
      val op0    = summon[EQPrimApi].op(ref.refer, that.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bool]:
        val _tpe:   Bool  = new Object with Bool
        val _refer: Value = nodeOp.operation.getResult(0)

    def =/=[RHS <: Referable[Bool]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] =
      val op0    = summon[NEQPrimApi].op(ref.refer, that.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bool]:
        val _tpe:   Bool  = new Object with Bool
        val _refer: Value = nodeOp.operation.getResult(0)

    def &[RHS <: Referable[Bool]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] =
      val op0    = summon[AndPrimApi].op(ref.refer, that.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bool]:
        val _tpe:   Bool  = new Object with Bool
        val _refer: Value = nodeOp.operation.getResult(0)

    def |[RHS <: Referable[Bool]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] =
      val op0    = summon[OrPrimApi].op(ref.refer, that.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bool]:
        val _tpe:   Bool  = new Object with Bool
        val _refer: Value = nodeOp.operation.getResult(0)

    def ^[RHS <: Referable[Bool]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] =
      val op0    = summon[XorPrimApi].op(ref.refer, that.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bool]:
        val _tpe:   Bool  = new Object with Bool
        val _refer: Value = nodeOp.operation.getResult(0)

    def ?[Ret <: Data](
      con: Referable[Ret],
      alt: Referable[Ret]
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Ret] =
      val op0    = summon[MuxPrimApi].op(ref.refer, con.refer, alt.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Ret]:
        val _tpe:   Ret   = con._tpe
        val _refer: Value = nodeOp.operation.getResult(0)
end given
