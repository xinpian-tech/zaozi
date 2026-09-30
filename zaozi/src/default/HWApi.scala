// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.HWApi

import org.llvm.circt.scalalib.dialect.hw.operation.{
  BitcastApi,
  ConstantApi,
  InstanceApi,
  Module,
  ModuleApi,
  ModuleExternApi,
  OutputApi,
  Port,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Type, Value, given}

import java.lang.foreign.Arena

given HWApi with
  def constant(
    value: BigInt,
    width: Int
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Value =
    val op = summon[ConstantApi].op(value, width, locate).operation
    op.appendToBlock()
    op.getResult(0)

  def bitcast(
    input:      Value,
    resultType: Type
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Value =
    val op = summon[BitcastApi].op(input, resultType, locate).operation
    op.appendToBlock()
    op.getResult(0)

  def module(
    symbol:      String,
    ports:       Seq[Port]
  )(body:        (Arena, Context, Block) ?=> Unit
  )(
    using arena: Arena,
    context:     Context,
    parent:      Block,
    sourceFile:  sourcecode.File,
    sourceLine:  sourcecode.Line
  ): Unit =
    val module = summon[ModuleApi].op(symbol, ports, locate)
    module.operation.appendToBlock()(
      using parent
    )
    body(
      using arena,
      context,
      module.block
    )

  def moduleExtern(
    symbol:      String,
    ports:       Seq[Port],
    verilogName: Option[String]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    summon[ModuleExternApi].op(symbol, ports, verilogName, locate).operation.appendToBlock()

  def instance(
    instanceName: String,
    moduleName:   String,
    ports:        Seq[Port],
    inputs:       Seq[Value]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Seq[Value] =
    val instance = summon[InstanceApi].op(instanceName, moduleName, ports, inputs, locate)
    instance.operation.appendToBlock()
    instance.results

  def instance(
    instanceName: String,
    module:       Module,
    inputs:       Seq[Value]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Seq[Value] = instance(instanceName, module.symbol, module.ports, inputs)

  def output(
    values: Seq[Value]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    summon[OutputApi].op(values, locate).operation.appendToBlock()
end given
