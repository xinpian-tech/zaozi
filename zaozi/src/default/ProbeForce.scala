// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.zaozi.default

import java.lang.foreign.Arena
import me.jiuyang.zaozi.{LayerTree, ProbeForce}
import me.jiuyang.zaozi.reftpe.Referable
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  RefForceApi,
  RefForceInitialApi,
  RefReleaseApi,
  RefReleaseInitialApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, given}

given [D <: Data & CanProbe]: ProbeForce[D] with
  extension (ref: Referable[RWProbe[D]])
    def force(
      value:  Referable[D],
      clock:  Referable[Clock],
      enable: Referable[Bool]
    )(
      using Arena,
      Context,
      Block,
      LayerTree,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      summon[RefForceApi].op(clock.refer, enable.refer, ref.refer, value.refer, locate).operation.appendToBlock()

    def release(
      clock:  Referable[Clock],
      enable: Referable[Bool]
    )(
      using Arena,
      Context,
      Block,
      LayerTree,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      summon[RefReleaseApi].op(clock.refer, enable.refer, ref.refer, locate).operation.appendToBlock()

    def forceInitial(
      value:  Referable[D],
      enable: Referable[Bool]
    )(
      using Arena,
      Context,
      Block,
      LayerTree,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      summon[RefForceInitialApi].op(enable.refer, ref.refer, value.refer, locate).operation.appendToBlock()

    def releaseInitial(
      enable: Referable[Bool]
    )(
      using Arena,
      Context,
      Block,
      LayerTree,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      summon[RefReleaseInitialApi].op(enable.refer, ref.refer, locate).operation.appendToBlock()
