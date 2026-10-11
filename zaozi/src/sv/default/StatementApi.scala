// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.default.locate
import me.jiuyang.zaozi.sv.{StatementApi, TypeImpl}
import me.jiuyang.zaozi.sv.valuetpe.{Bits, Data}
import me.jiuyang.zaozi.sv.reftpe.{Ref, Referable}
import org.llvm.circt.scalalib.dialect.sv.operation.{
  AlwaysCombApi,
  AlwaysFFApi,
  AssignApi,
  BPAssignApi,
  PAssignApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

given StatementApi with
  def alwaysFF(
    clock: Referable[Bits],
    edge:  org.llvm.circt.scalalib.dialect.sv.attr.EventControl =
      org.llvm.circt.scalalib.dialect.sv.attr.EventControl.PosEdge
  )(body:  Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val op = summon[AlwaysFFApi].op(edge, clock.refer, locate)
    op.operation.appendToBlock()
    body(
      using op.block
    )
  def alwaysComb(
    body: Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val op = summon[AlwaysCombApi].op(locate)
    op.operation.appendToBlock()
    body(
      using op.block
    )
  extension [T <: Data](dest: Ref[T])
    /** Continuous at module scope; blocking inside a procedural region. */
    def :=(
      src: Referable[T]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      val op =
        if procedural(summon[Block]) then summon[BPAssignApi].op(dest._refer, src.refer, locate).operation
        else summon[AssignApi].op(dest._refer, src.refer, locate).operation
      op.appendToBlock()
    def :==(
      src: Referable[T]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      summon[PAssignApi].op(dest._refer, src.refer, locate).operation.appendToBlock()
