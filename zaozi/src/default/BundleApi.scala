// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.UntypedDynamicSubfield
import me.jiuyang.zaozi.reftpe.{Const, Node, Propagated, Ref, Referable}
import me.jiuyang.zaozi.valuetpe.{Bits, Bundle, Data, ProbeBundle, ProbeRecord, Record}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{*, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{given_BitCastApi, given_WireApi, BitCastApi, WireApi}
import org.llvm.mlir.scalalib.capi.ir.{*, given}

import java.lang.foreign.Arena

/** Implements `BundleApi`: `asBits` is a `firrtl.bitcast` to a same-width `Bits`, not a field-by-field walk. */
given [T <: Bundle | ProbeBundle]: BundleApi[T] with
  extension [R <: Referable[T]](ref: R)
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

end given
