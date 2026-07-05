// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.MonoConnect
import me.jiuyang.zaozi.reftpe.{Referable, Writable}
import me.jiuyang.zaozi.valuetpe.Data

import org.llvm.circt.scalalib.dialect.firrtl.operation.{ConnectApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, given}

import java.lang.foreign.Arena

given [D <: Data, SRC <: Referable[D], SINK <: Writable[D]]: MonoConnect[D, SRC, SINK] with
  extension (ref: SINK)
    def :=(
      that: SRC
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      summon[ConnectApi]
        .op(
          that.refer,
          ref.refer,
          locate
        )
        .operation
        .appendToBlock()
