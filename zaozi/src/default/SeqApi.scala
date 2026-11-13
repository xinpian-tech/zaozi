// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.SeqApi

import org.llvm.circt.scalalib.capi.dialect.seq.{TypeApi, given}
import org.llvm.circt.scalalib.dialect.seq.operation.{ClockInvApi, ToClockApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Type, Value, given}

import java.lang.foreign.Arena

given SeqApi with
  def clockType(
    using Arena,
    Context
  ): Type = summon[TypeApi].clockTypeGet

  extension (input: Value)
    def toClock(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      val result = summon[ToClockApi].op(input, locate)
      result.operation.appendToBlock()
      result.result

    def clockInv(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      val result = summon[ClockInvApi].op(input, locate)
      result.operation.appendToBlock()
      result.result
end given
