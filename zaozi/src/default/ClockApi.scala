// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.{ClockApi, InstanceContext}

import org.llvm.circt.scalalib.capi.dialect.firrtl.FirrtlNameKind
import org.llvm.circt.scalalib.dialect.firrtl.operation.{AsUIntPrimApi, NodeApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, given}

import java.lang.foreign.Arena

given ClockApi with
  extension [R <: Referable[Clock]](ref: R)
    def asBool(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, Bool] =
      val asUIntOp = summon[AsUIntPrimApi].op(ref.refer, locate)
      asUIntOp.operation.appendToBlock()
      val nodeOp   = summon[NodeApi].op(
        name = valName,
        location = locate,
        nameKind = FirrtlNameKind.Interesting,
        input = asUIntOp.result
      )
      nodeOp.operation.appendToBlock()
      propagate[R, Bool](ref, new Object with Bool, nodeOp.operation.getResult(0))
end given
