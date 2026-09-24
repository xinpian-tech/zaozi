// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{DpiArg, DpiCallResult, DpiFunction, SimApi}

import org.llvm.circt.scalalib.dialect.sim.operation.{
  ClockedTerminateApi,
  DPIArgument,
  DPICallApi,
  DPIDirection,
  DPIFuncApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, TypeApi, Value, given}

import java.lang.foreign.Arena

given SimApi with
  def dpiFunction(
    symbol:    String,
    cName:     Option[String],
    arguments: Seq[DpiArg]
  )(
    using Arena,
    Context,
    Block
  ): DpiFunction =
    require(arguments.count(_.direction == DPIDirection.Return) == 1, "DPI function needs one return value")
    val function = summon[DPIFuncApi].op(
      symbol = symbol,
      verilogName = cName,
      arguments = arguments.map { arg =>
        val argType = if arg.signed then arg.width.integerTypeSignedGet else arg.width.integerTypeGet
        DPIArgument(arg.name, arg.direction, argType)
      },
      location = locate
    )
    function.operation.appendToBlock()
    DpiFunction(symbol, arguments)

  def dpiCall(
    function: DpiFunction,
    clock:    Value,
    enabled:  Value,
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
      resultTypes = outArgs.map(arg => if arg.signed then arg.width.integerTypeSignedGet else arg.width.integerTypeGet),
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
