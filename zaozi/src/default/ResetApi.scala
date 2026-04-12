// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.{InstanceContext, ResetApi}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_TypeApi, FirrtlNameKind}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{NodeApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, Operation, given}

import java.lang.foreign.Arena
import org.llvm.circt.scalalib.dialect.firrtl.operation.AsUIntPrimApi

given ResetApi with
  extension [R <: Referable[Reset]](ref: R)
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
        input = asUIntOp.operation.getResult(0)
      )
      nodeOp.operation.appendToBlock()
      propagate[R, Bool](ref, new Object with Bool, nodeOp.operation)
end given
