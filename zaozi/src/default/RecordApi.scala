// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.UntypedDynamicSubfield
import me.jiuyang.zaozi.reftpe.{Const, Node, Propagated, Ref, Referable}
import me.jiuyang.zaozi.valuetpe.{Bits, Data, ProbeRecord, Record}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{*, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{given_BitCastApi, given_NodeApi, BitCast, BitCastApi, NodeApi}
import org.llvm.mlir.scalalib.capi.ir.{*, given}

import java.lang.foreign.Arena

given [T <: Record | ProbeRecord, R <: Referable[T]]: RecordApi[T, R] with
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

    def field[T <: Data](
      fieldName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[T] = ref._tpe.getUntypedRefViaFieldValName(ref.refer, fieldName).asInstanceOf[Ref[T]]

end given
