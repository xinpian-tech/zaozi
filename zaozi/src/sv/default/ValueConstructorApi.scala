// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.default.{locate, valName}
import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.circt.scalalib.dialect.sv.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

given ValueConstructorApi with
  def X(
    tpe: Bits
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[Bits] =
    emitted(tpe, summon[ConstantXApi].op(tpe.toMlirType, locate).operation)
  def Z(
    tpe: Bits
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[Bits] =
    emitted(tpe, summon[ConstantZApi].op(tpe.toMlirType, locate).operation)
  extension (value:            String)
    def S(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Node[SVString.type] =
      emitted(SVString, summon[ConstantStrApi].op(value, locate).operation)
  def concatStrings(
    values: Referable[SVString.type]*
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[SVString.type] =
    emitted(SVString, summon[ConcatStrApi].op(values.map(_.refer), locate).operation)
  def formatString(
    format: String,
    values: Referable[?]*
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[SVString.type] =
    emitted(SVString, summon[SFormatFApi].op(format, values.map(_.refer), locate).operation)
  def PackedArrayValue[T <: Data](
    tpe:    PackedArray[T],
    values: Seq[Referable[T]]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[PackedArray[T]] =
    import org.llvm.circt.scalalib.dialect.hw.operation.given
    emitted(
      tpe,
      summon[org.llvm.circt.scalalib.dialect.hw.operation.ArrayCreateApi]
        .op(values.reverse.map(_.refer), tpe.toMlirType, locate)
        .operation
    )
  def UnpackedArrayValue[T <: Data](
    tpe:    UnpackedArray[T],
    values: Seq[Referable[T]]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[UnpackedArray[T]] =
    emitted(tpe, summon[UnpackedArrayCreateApi].op(values.reverse.map(_.refer), tpe.toMlirType, locate).operation)
  extension [T <: Data](value: Referable[UnpackedArray[T]])
    def asOpenArray(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Node[UnpackedOpenArray[T]] =
      val tpe = UnpackedOpenArray(value.getType._element)
      emitted(tpe, summon[UnpackedOpenArrayCastApi].op(value.refer, tpe.toMlirType, locate).operation)
  extension (value:            Referable[Bits])
    /** Inclusive high/low indices, matching Zaozi's bit-range convention. */
    def bits(
      hi: Int,
      lo: Int
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      value.bits(BigInt(lo).B(32), hi - lo + 1)
    def bits(
      base:      Referable[Bits],
      width:     Int,
      decrement: Boolean = false
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits] =
      emitted(Bits(width), summon[IndexedPartSelectApi].op(value.refer, base.refer, width, decrement, locate).operation)
  extension (value:            Ref[Bits])
    /** Inclusive high/low indices, matching Zaozi's bit-range convention. */
    def ref(
      hi: Int,
      lo: Int
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Ref[Bits] =
      value.ref(BigInt(lo).B(32), hi - lo + 1)
    def ref(
      base:      Referable[Bits],
      width:     Int,
      decrement: Boolean = false
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Ref[Bits] =
      val op = summon[IndexedPartSelectInOutApi].op(value._refer, base.refer, width, decrement, locate)
      op.operation.appendToBlock()
      new Ref[Bits]:
        private[zaozi] val _tpe   = Bits(width)
        private[zaozi] val _refer = op.operation.getResult(0)
