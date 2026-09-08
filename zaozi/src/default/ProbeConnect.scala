// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import scala.annotation.targetName

import me.jiuyang.zaozi.reftpe.Referable
import me.jiuyang.zaozi.valuetpe.{CanProbe, Data, RProbe, RWProbe}
import me.jiuyang.zaozi.{LayerTree, ProbeConnect}

import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  ConnectApi,
  RefCastApi,
  RefDefineApi,
  RefResolveApi,
  RefSendApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value, given}

import java.lang.foreign.Arena

given [D <: Data & CanProbe, P <: RWProbe[D] | RProbe[D], DATA <: Referable[D], PROBE <: Referable[P]]
  : ProbeConnect[D, P, DATA, PROBE] with
  private def source(
    ref:  PROBE,
    that: DATA
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Value = ref.getType match
    case _: RWProbe[?] =>
      val op = that.definingOp.getOrElse(
        throw IllegalArgumentException("RWProbe binding requires a forceable Wire, Reg, or Node")
      )
      require(
        Set("firrtl.wire", "firrtl.reg", "firrtl.regreset", "firrtl.node").contains(op.getName.str) &&
          op.getNumResults == 2,
        "RWProbe binding requires a whole Wire, Reg, or Node created with forceable = true"
      )
      op.getResult(1)
    case _: RProbe[?]  =>
      val op = summon[RefSendApi].op(that.refer, locate)
      op.operation.appendToBlock()
      op.result

  extension (ref: PROBE)
    @targetName("send")
    def <==(
      that: DATA
    )(
      using Arena,
      Context,
      Block,
      LayerTree,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      val refCastOp   = summon[RefCastApi].op(source(ref, that), ref.refer.getType, locate)
      val refDefineOp = summon[RefDefineApi].op(ref.refer, refCastOp.result, locate)
      refCastOp.operation.appendToBlock()
      refDefineOp.operation.appendToBlock()

    @targetName("define")
    def <==(
      that: PROBE
    )(
      using Arena,
      Context,
      Block,
      LayerTree,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      val refDefineOp = summon[RefDefineApi]
        .op(
          ref.refer,
          that.refer,
          locate
        )
      refDefineOp.operation.appendToBlock()

  extension (ref: DATA)
    @targetName("resolve")
    def <==(
      that: PROBE
    )(
      using Arena,
      Context,
      Block,
      LayerTree,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      val refResolveOp = summon[RefResolveApi]
        .op(
          that.refer,
          locate
        )
      refResolveOp.operation.appendToBlock()
      summon[ConnectApi]
        .op(
          refResolveOp.result,
          ref.refer,
          locate
        )
        .operation
        .appendToBlock()
