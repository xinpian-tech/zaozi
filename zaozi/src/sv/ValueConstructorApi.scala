// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.mlir.scalalib.capi.ir.*
import java.lang.foreign.Arena

trait ValueConstructorApi:
  def X(
    tpe: Bits
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ):   Node[Bits]
  def Z(
    tpe: Bits
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ):   Node[Bits]
  extension (value: String)
    def S(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Node[SVString.type]
  def concatStrings(
    values: Referable[SVString.type]*
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ):   Node[SVString.type]
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
  ):   Node[SVString.type]

  /** Values are supplied in ascending index order, starting at zero. */
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
  ):   Node[PackedArray[T]]
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
  ):   Node[UnpackedArray[T]]
  extension [T <: Data](value: Referable[UnpackedArray[T]])
    def asOpenArray(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Node[UnpackedOpenArray[T]]
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
    ): Node[Bits]
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
    ): Node[Bits]
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
    ): Ref[Bits]
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
    ): Ref[Bits]
