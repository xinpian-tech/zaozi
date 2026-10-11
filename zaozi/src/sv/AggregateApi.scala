// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import java.lang.foreign.Arena

trait AggregateApi:
  def StructValue[T <: Data](
    tpe:    T,
    fields: Seq[FieldBinding]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[T]
  def UnionValue[T <: Data, E <: Data](
    tpe:   T,
    field: Field[E],
    value: Referable[E]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[T]
  def fieldRef[T <: Data, E <: Data](
    ref:  Ref[T],
    name: String
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Ref[E]
  def fieldValue[T <: Data, E <: Data](
    ref:  Referable[T],
    name: String
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[E]
  def indexRef[T <: Data, E <: Data](
    ref:   Ref[T],
    index: Referable[Bits]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Ref[E]
  def indexValue[T <: Data, E <: Data](
    ref:   Referable[T],
    index: Referable[Bits]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[E]

  /** Resolve a declared SV interface signal through the same field-access macro as aggregates. */
  def interfaceField[T <: Data, E <: Data](
    ref:  Referable[T],
    name: String
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): InterfaceSignalRef[E]
  def interfaceModport[T <: Data, I <: VerilogInterface](
    ref:  Referable[T],
    name: String
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[Modport[I]]
