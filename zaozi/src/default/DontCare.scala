// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.{Interface, Writable}
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.{UtilityApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, given}

import java.lang.foreign.Arena

private[zaozi] def interfaceDontCare(
  sink: Interface[?]
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line
): Unit =
  sink._ports.foreach(port => summon[UtilityApi].emitInvalidate(summon[Block], locate, port))

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
      summon[UtilityApi].emitInvalidate(summon[Block], locate, ref.refer)
