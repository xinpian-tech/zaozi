// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{SVApi, SVCase}

import org.llvm.circt.scalalib.dialect.sv.operation.{
  AssignApi,
  BPAssignApi,
  CaseApi,
  ConstantStrApi,
  IfApi,
  InitialApi,
  PAssignApi,
  ReadInOutApi,
  RegApi,
  VerbatimApi,
  WireApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Type, Value, given}

import java.lang.foreign.Arena

given SVApi with
  def initial(
    body: Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val op = summon[InitialApi].op(locate)
    op.operation.appendToBlock()
    body(
      using op.block
    )

  def ifElse(
    condition: Value
  )(thenBody:  Block ?=> Unit
  )(elseBody:  Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val op = summon[IfApi].op(condition, locate)
    op.operation.appendToBlock()
    thenBody(
      using op.thenBlock
    )
    elseBody(
      using op.elseBlock
    )

  def switch(
    selector:    Value,
    cases:       Seq[SVCase]
  )(defaultBody: Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val op = summon[CaseApi].op(selector, cases.map(_.value), locate)
    op.operation.appendToBlock()
    cases.zipWithIndex.foreach((branch, index) =>
      branch.body(
        using op.block(index)
      )
    )
    defaultBody(
      using op.block(cases.size)
    )

  def stringConstant(
    value: String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Value =
    val op = summon[ConstantStrApi].op(value, locate).operation
    op.appendToBlock()
    op.getResult(0)

  def wire(
    elementType: Type,
    name:        String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Value =
    val op = summon[WireApi].op(elementType, name, locate).operation
    op.appendToBlock()
    op.getResult(0)

  def assign(
    destination: Value,
    source:      Value
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    summon[AssignApi].op(destination, source, locate).operation.appendToBlock()

  def blockingAssign(
    destination: Value,
    source:      Value
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    summon[BPAssignApi].op(destination, source, locate).operation.appendToBlock()

  def nonBlockingAssign(
    destination: Value,
    source:      Value
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    summon[PAssignApi].op(destination, source, locate).operation.appendToBlock()

  def reg(
    elementType: Type,
    name:        String,
    init:        Option[Value]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Value =
    val result = summon[RegApi].op(elementType, name, init, locate)
    result.operation.appendToBlock()
    result.result

  extension (input: Value)
    def readInOut(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      val result = summon[ReadInOutApi].op(input, locate)
      result.operation.appendToBlock()
      result.result

  def verbatim(
    formatString:  String,
    substitutions: Seq[Value]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    summon[VerbatimApi].op(formatString, substitutions, locate).operation.appendToBlock()
end given
