// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.{InstanceContext, VecApi}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_FirrtlBundleFieldApi, given_TypeApi, FirrtlNameKind}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{BitCastApi, NodeApi, SubaccessApi, SubindexApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, Operation, given}

import java.lang.foreign.Arena

given [E <: Data, V <: Vec[E], R <: Referable[V]]: VecApi[E, V, R] with
  extension (ref: R)
    def asBits(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, Bits] =
      val bitcastOp = summon[BitCastApi].op(
        input = ref.refer,
        tpe = Bits(ref.refer.getType.getBitWidth(true).toInt).toMlirType,
        location = locate
      )
      bitcastOp.operation.appendToBlock()
      val tpe       = new Bits:
        private[zaozi] val _width = bitcastOp.operation.getResult(0).getType.getBitWidth(true).toInt
      propagate[R, Bits](ref, tpe, bitcastOp.operation)

    def length(
      using Arena,
      Context
    ) = ref.refer.getType.getVectorElementNum.toInt

    def ref(
      idx: Referable[UInt] | Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Ref[E] =
      val refOp = idx match
        case that: Referable[UInt] =>
          val op0 = summon[SubaccessApi].op(ref.refer, that.refer, locate)
          op0.operation.appendToBlock()
          op0.operation
        case that: Int             =>
          val op0 = summon[SubindexApi].op(ref.refer, that, locate)
          op0.operation.appendToBlock()
          op0.operation
      new Ref[E]:
        val _tpe:       E         = ref._tpe._elementType
        val _operation: Operation = refOp

    // sugars
    def apply(
      idx: Referable[UInt] | Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Ref[E] = ref.ref(idx)

end given
