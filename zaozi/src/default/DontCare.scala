// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.DontCare
import me.jiuyang.zaozi.reftpe.Writable
import me.jiuyang.zaozi.valuetpe.Data

import org.llvm.circt.scalalib.dialect.firrtl.operation.{ConnectApi, InvalidValueApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, given}

import java.lang.foreign.Arena

given [D <: Data, SINK <: Writable[D]]: DontCare[D, SINK] with
  extension (ref: SINK)
    def dontCare(
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      val invalidOp = summon[InvalidValueApi]
        .op(
          ref.refer.getType,
          locate
        )
      invalidOp.operation.appendToBlock()
      summon[ConnectApi]
        .op(
          invalidOp.result,
          ref.refer,
          locate
        )
        .operation
        .appendToBlock()
