// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{DpiArg, DpiCallResult, DpiFunction, SVApi, SVCase}

import org.llvm.circt.scalalib.capi.dialect.hw.given
import org.llvm.circt.scalalib.capi.dialect.sim.{DPIArgumentApi, DPIDirection, given}
import org.llvm.circt.scalalib.dialect.seq.operation.{FromClockApi, given}
import org.llvm.circt.scalalib.dialect.sv.operation.{
  AlwaysApi,
  AssignApi,
  BPAssignApi,
  CaseApi,
  EventControl,
  FuncApi,
  FuncCallProceduralApi,
  FuncDPIImportApi,
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
  def onClock(
    clock:   Value,
    enabled: Option[Value]
  )(body:    Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val trigger = summon[FromClockApi].op(clock, locate)
    trigger.operation.appendToBlock()
    val process = summon[AlwaysApi].op(Seq(EventControl.PosEdge), Seq(trigger.result), locate)
    process.operation.appendToBlock()
    locally:
      given Block = process.block
      enabled match
        case Some(condition) => ifElse(condition)(body)()
        case None            => body

  def dpiFunction(
    symbol:    String,
    cName:     Option[String],
    arguments: Seq[DpiArg]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): DpiFunction =
    summon[FuncApi].op(
      symbol = symbol,
      verilogName = cName,
      arguments = arguments.map { arg =>
        val tpe = if arg.signed then arg.width.integerTypeSignedGet else arg.width.integerTypeGet
        summon[DPIArgumentApi].createDPIArgument(arg.name, tpe, arg.direction)
      },
      location = locate
    ).operation.appendToBlock()
    summon[FuncDPIImportApi].op(symbol, None, locate).operation.appendToBlock()
    DpiFunction(symbol, arguments)

  def dpiCallProcedural(
    function: DpiFunction,
    inputs:   Seq[Value]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): DpiCallResult =
    val inArgs = function.arguments.filter(arg =>
      arg.direction == DPIDirection.In || arg.direction == DPIDirection.InOut
    )
    val outArgs = function.arguments.filter(arg =>
      arg.direction == DPIDirection.Out || arg.direction == DPIDirection.Return
    )
    require(inArgs.size == inputs.size, "DPI input count does not match the declaration")
    inArgs.zip(inputs).foreach: (arg, input) =>
      val tpe = if arg.signed then arg.width.integerTypeSignedGet else arg.width.integerTypeGet
      val expected = if arg.direction == DPIDirection.InOut then tpe.inOutTypeGet() else tpe
      require(input.getType.equal(expected), s"DPI input type mismatch: ${arg.name}")
    val call = summon[FuncCallProceduralApi].op(
      callee = function.symbol,
      inputs = inputs,
      resultTypes = outArgs.map(arg => if arg.signed then arg.width.integerTypeSignedGet else arg.width.integerTypeGet),
      location = locate
    )
    call.operation.appendToBlock()
    DpiCallResult(outArgs.zipWithIndex.map((arg, index) => arg.name -> call.operation.getResult(index)).toMap)

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
