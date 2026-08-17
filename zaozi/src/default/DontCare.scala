// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.{Interface, Referable, Writable}
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.given
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
    ): Unit = ref match
      case iface: Interface[?] =>
        iface._ports.foreach(_.emitInvalidate(summon[Block], locate))
      case r:     Referable[?] =>
        r.refer.emitInvalidate(summon[Block], locate)
      case other =>
        throw ConnectException(s"unsupported dontCare sink representation: ${other.getClass.getName}")
