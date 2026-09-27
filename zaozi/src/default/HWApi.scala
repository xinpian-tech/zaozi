// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.HWApi

import org.llvm.circt.scalalib.dialect.hw.operation.{InstanceApi, ModuleApi, ModuleExternApi, OutputApi, Port, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value, given}

import java.lang.foreign.Arena

given HWApi with
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
