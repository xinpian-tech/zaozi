// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import scala.annotation.targetName

import me.jiuyang.zaozi.reftpe.Referable
import me.jiuyang.zaozi.valuetpe.{CanProbe, Data, RProbe, RWProbe}
import me.jiuyang.zaozi.{nameHierarchy, LayerTree, ProbeConnect}

import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  ConnectApi,
  RefCastApi,
  RefDefineApi,
  RefResolveApi,
  RefSendApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, given}

import java.lang.foreign.Arena

/** Implements `ProbeConnect`'s three `<==` overloads via FIRRTL's `ref.send`/`ref.cast`/`ref.define`/ `ref.resolve`
  * ops: send+define to originate a probe from data, plain define to alias one probe to another, and resolve+connect to
  * read a probe back into an ordinary value. See `me.jiuyang.zaozi.ProbeConnect` in `me.jiuyang.zaozi.Api`.
  */
given [D <: Data & CanProbe, P <: RWProbe[D] | RProbe[D], DATA <: Referable[D], PROBE <: Referable[P]]
  : ProbeConnect[D, P, DATA, PROBE] with
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
      val refSendOp   = summon[RefSendApi]
        .op(
          that.refer,
          locate
        )
      val refCastOp   = summon[RefCastApi]
        .op(refSendOp.result, ref.refer.getType, locate)
      val refDefineOp = summon[RefDefineApi]
        .op(
          ref.refer,
          refCastOp.result,
          locate
        )
      refSendOp.operation.appendToBlock()
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
