// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{DpiArg, DpiCallResult, DpiFunction, DpiType, SimApi}
import org.llvm.circt.scalalib.capi.dialect.hw.{TypeApi as HWTypeApi, given}

import org.llvm.circt.scalalib.dialect.sim.operation.{
  ClockedTerminateApi,
  DPIArgument,
  DPICallApi,
  DPICallProcApi,
  DPIDirection,
  DPIFuncApi,
  TerminateApi,
  TriggeredApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, Type, TypeApi, Value, given}

import java.lang.foreign.Arena

given SimApi with
  private def argumentType(
    arg: DpiArg
  )(
    using Arena,
    Context
  ): Type = arg.tpe match
    case DpiType.Integer(width, signed) => if signed then width.integerTypeSignedGet else width.integerTypeGet
    case DpiType.String                 => summon[HWTypeApi].stringTypeGet

  def terminate(
    success: Boolean
  )(
    using Arena,
    Context,
    Block
  ): Unit =
    summon[TerminateApi].op(success, false, locate).operation.appendToBlock()

  def triggered(
    clock:   Value,
    enabled: Option[Value]
  )(body:    Block ?=> Unit
  )(
    using Arena,
    Context,
    Block
  ): Unit =
    val process = summon[TriggeredApi].op(clock, enabled, locate)
    process.operation.appendToBlock()
    body(
      using process.block
    )

  def dpiFunction(
    symbol:    String,
    cName:     Option[String],
    arguments: Seq[DpiArg]
  )(
    using Arena,
    Context,
    Block
  ): DpiFunction =
    require(arguments.count(_.direction == DPIDirection.Return) <= 1, "DPI function has at most one return value")
    val function = summon[DPIFuncApi].op(
      symbol = symbol,
      verilogName = cName,
      arguments = arguments.map { arg =>
        val argType = argumentType(arg)
        DPIArgument(arg.name, arg.direction, argType)
      },
      location = locate
    )
    function.operation.appendToBlock()
    DpiFunction(symbol, arguments)

  def dpiCall(
    function: DpiFunction,
    clock:    Value,
    enabled:  Option[Value],
    inputs:   Seq[Value]
  )(
    using Arena,
    Context,
    Block
  ): DpiCallResult =
    val inArgs  =
      function.arguments.filter(arg => arg.direction == DPIDirection.In || arg.direction == DPIDirection.InOut)
    val outArgs = function.arguments.filter(arg => arg.direction != DPIDirection.In)
    require(inArgs.size == inputs.size, "DPI input count does not match the declaration")
    val call    = summon[DPICallApi].op(
      callee = function.symbol,
      clock = clock,
      enable = enabled,
      inputs = inputs,
      resultTypes = outArgs.map(argumentType),
      location = locate
    )
    call.operation.appendToBlock()
    DpiCallResult(outArgs.zipWithIndex.map((arg, index) => arg.name -> call.operation.getResult(index)).toMap)

  def dpiCallProcedural(
    function: DpiFunction,
    inputs:   Seq[Value]
  )(
    using Arena,
    Context,
    Block
  ): DpiCallResult =
    val inArgs  =
      function.arguments.filter(arg => arg.direction == DPIDirection.In || arg.direction == DPIDirection.InOut)
    val outArgs = function.arguments.filter(arg => arg.direction != DPIDirection.In)
    require(inArgs.size == inputs.size, "DPI input count does not match the declaration")
    val call    = summon[DPICallProcApi].op(
      callee = function.symbol,
      inputs = inputs,
      resultTypes = outArgs.map(argumentType),
      location = locate
    )
    call.operation.appendToBlock()
    DpiCallResult(outArgs.zipWithIndex.map((arg, index) => arg.name -> call.operation.getResult(index)).toMap)

  def clockedTerminate(
    clock:     Value,
    condition: Value,
    success:   Boolean
  )(
    using Arena,
    Context,
    Block
  ): Unit =
    summon[ClockedTerminateApi]
      .op(clock = clock, condition = condition, success = success, verbose = true, location = locate)
      .operation
      .appendToBlock()
