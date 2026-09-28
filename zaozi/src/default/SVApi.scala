// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.SVApi

import org.llvm.circt.scalalib.dialect.sv.operation.{ReadInOutApi, RegApi, VerbatimApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Type, Value, given}

import java.lang.foreign.Arena

given SVApi with
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
