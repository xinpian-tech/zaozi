// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.{BitsApi, InstanceContext}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_FirrtlBundleFieldApi, given_TypeApi, FirrtlNameKind}
import org.llvm.circt.scalalib.dialect.firrtl.operation.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  given_BitCastApi,
  AndPrimApi,
  AndRPrimApi,
  AsSIntPrimApi,
  BitCast,
  BitCastApi,
  BitsPrimApi,
  CatPrimApi,
  ConnectApi,
  DShlPrimApi,
  DShrPrimApi,
  EQPrimApi,
  HeadPrimApi,
  NEQPrimApi,
  NodeApi,
  NotPrimApi,
  OrPrimApi,
  OrRPrimApi,
  PadPrimApi,
  ShlPrimApi,
  ShrPrimApi,
  TailPrimApi,
  WireApi,
  XorPrimApi,
  XorRPrimApi
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, Operation, given}

import java.lang.foreign.Arena

given [LHS <: Referable[Bits], RHS <: Referable[Bits]]: BitsApi[LHS, RHS] with
  extension (ref: LHS)
    def asSInt(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[LHS, SInt] =
      val op0    = summon[AsSIntPrimApi].op(ref.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      val tpe    = new SInt:
        private[zaozi] val _width = op0.result.getType.getBitWidth(true).toInt
      propagate[LHS, SInt](ref, tpe, nodeOp.operation)
    def asUInt(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[LHS, UInt] =
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = ref.refer
      )
      nodeOp.operation.appendToBlock()
      val tpe    = new UInt:
        private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
      propagate[LHS, UInt](ref, tpe, nodeOp.operation)
    def asBool(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[LHS, Bool] =
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = ref.refer
      )
      val width  = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
      require(
        width == 1,
        s"Cannot convert ${summon[sourcecode.Name.Machine]}: Bits(${width}) to Bool"
      )
      nodeOp.operation.appendToBlock()
      propagate[LHS, Bool](ref, new Object with Bool, nodeOp.operation)
    def asBundle[T <: Bundle](
      tpe: T
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[LHS, T] =
      val bitcastOp = summon[BitCastApi].op(
        input = ref.refer,
        tpe = tpe.toMlirType,
        location = locate
      )
      bitcastOp.operation.appendToBlock()
      propagate[LHS, T](ref, tpe, bitcastOp.operation)
    def asRecord[T <: Record](
      tpe: T
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[LHS, T] =
      val bitcastOp = summon[BitCastApi].op(
        input = ref.refer,
        tpe = tpe.toMlirType,
        location = locate
      )
      bitcastOp.operation.appendToBlock()
      propagate[LHS, T](ref, tpe, bitcastOp.operation)
    def asVec[E <: Data](
      tpe: E
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[LHS, Vec[E]] =
      val srcWidth  = ref.refer.getType.getBitWidth(true).toInt
      val dstWidth  = tpe.toMlirType.getBitWidth(true).toInt
      require(
        srcWidth % dstWidth == 0,
        s"type cast to Vec[$tpe] failed: width $srcWidth cannot divide $dstWidth"
      )
      val count     = srcWidth / dstWidth
      val vecTpe    = Vec[E](count, tpe)
      val bitcastOp = summon[BitCastApi].op(
        input = ref.refer,
        tpe = vecTpe.toMlirType,
        location = locate
      )
      bitcastOp.operation.appendToBlock()
      propagate[LHS, Vec[E]](ref, Vec[E](count, tpe), bitcastOp.operation)
    def unary_~(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val op0    = summon[NotPrimApi].op(ref.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = op0.result.getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation

    def andR(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] =
      val op0    = summon[AndRPrimApi].op(ref.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = nodeOp.operation

    def orR(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] =
      val op0    = summon[OrRPrimApi].op(ref.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = nodeOp.operation

    def xorR(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] =
      val op0    = summon[XorRPrimApi].op(ref.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = nodeOp.operation

    def ===(
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
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = nodeOp.operation
    def =/=(
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
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = nodeOp.operation
    def &(
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val op0    = summon[AndPrimApi].op(ref.refer, that.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation
    def |(
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val op0    = summon[OrPrimApi].op(ref.refer, that.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation

    def ^(
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val op0    = summon[XorPrimApi].op(ref.refer, that.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation

    def ##(
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val op0    = summon[CatPrimApi].op(ref.refer, that.refer, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation
    def <<(
      that: Int | Referable[UInt]
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = that match
          case that: Int             =>
            val op0 = summon[ShlPrimApi].op(ref.refer, that, locate)
            op0.operation.appendToBlock()
            op0.result
          case that: Referable[UInt] =>
            val op0 = summon[DShlPrimApi].op(ref.refer, that.refer, locate)
            op0.operation.appendToBlock()
            op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation
    def >>(
      that: Int | Referable[UInt]
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = that match
          case that: Int             =>
            val op0 = summon[ShrPrimApi].op(ref.refer, that, locate)
            op0.operation.appendToBlock()
            op0.result
          case that: Referable[UInt] =>
            val op0 = summon[DShrPrimApi].op(ref.refer, that.refer, locate)
            op0.operation.appendToBlock()
            op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation

    def head(
      that: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val op0    = summon[HeadPrimApi].op(ref.refer, that, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation
    def tail(
      that: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val op0    = summon[TailPrimApi].op(ref.refer, that, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation
    def pad(
      that: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val op0    = summon[PadPrimApi].op(ref.refer, that, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation
    def bits(
      hi: Int,
      lo: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] =
      val op0    = summon[BitsPrimApi].op(ref.refer, hi, lo, locate)
      op0.operation.appendToBlock()
      val nodeOp = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = op0.result
      )
      nodeOp.operation.appendToBlock()
      new Node[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = nodeOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = nodeOp.operation

    def bit(
      idx: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] = bits(idx, idx).asBool

    // sugars
    def apply(
      hi: Int,
      lo: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bits] = bits(hi, lo)

    def apply(
      idx: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Bool] = bit(idx)
end given
