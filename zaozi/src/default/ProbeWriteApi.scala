// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Zaozi contributors
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{ClockScope, DVLayerScope, LayerTree, ProbeWriteApi}
import me.jiuyang.zaozi.reftpe.{ForceableReferable, ProbeWrite, Referable}
import me.jiuyang.zaozi.valuetpe.{Bool, CanProbe, Data}

import org.llvm.circt.scalalib.capi.dialect.firrtl.FirrtlEventControl
import org.llvm.circt.scalalib.dialect.firrtl.operation.{RefForceApi, RefReleaseApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, given}

import java.lang.foreign.Arena

export given_ProbeWriteApi.{force, release, ProbeWrite}

given given_DVLayerScope(
  using Arena,
  Block,
  LayerTree
): DVLayerScope =
  require(
    summon[Block].getParentOperation.getName.str == "firrtl.layerblock",
    "ProbeWrite requires a FIRRTL layer block"
  )
  new DVLayerScope

given ProbeWriteApi with
  def ProbeWrite[T <: Data & CanProbe](
    target: ForceableReferable[T]
  )(
    using Arena,
    Context,
    Block,
    DVLayerScope
  ): ProbeWrite[T] =
    new ProbeWrite[T]:
      val _tpe   = target._tpe
      val _refer = target._forceableRefer

  extension [T <: Data & CanProbe](probe: ProbeWrite[T])
    def force(
      predicate: Referable[Bool],
      value:     Referable[T]
    )(
      using ClockScope,
      Arena,
      Context,
      Block,
      DVLayerScope,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      require(
        summon[ClockScope].clockEdge == FirrtlEventControl.AtPosEdge,
        "clocked ProbeWrite supports posedge clocks only"
      )
      val op = summon[RefForceApi].op(
        summon[ClockScope].clock.refer,
        predicate.refer,
        probe._refer,
        value.refer,
        locate
      )
      op.operation.appendToBlock()

    def release(
      predicate: Referable[Bool]
    )(
      using ClockScope,
      Arena,
      Context,
      Block,
      DVLayerScope,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      require(
        summon[ClockScope].clockEdge == FirrtlEventControl.AtPosEdge,
        "clocked ProbeWrite supports posedge clocks only"
      )
      val op = summon[RefReleaseApi].op(
        summon[ClockScope].clock.refer,
        predicate.refer,
        probe._refer,
        locate
      )
      op.operation.appendToBlock()
