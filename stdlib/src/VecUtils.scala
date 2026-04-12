// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

trait VecUtilsApi:
  extension [E <: Data, V <: Vec[E], R <: Referable[V]](ref: R)
    // Each element access emits a SubindexOp into the Block captured at the toSeq call site,
    // not the Block at the point of access. This matters inside when/otherwise.
    def toSeq(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext,
      VecApi[E, V]
    ): IndexedSeq[Ref[E]]

  extension [E <: Data](seq: Seq[Referable[E]])
    def toVec(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext,
      ConstructorApi
    ): Node[Vec[E]]
