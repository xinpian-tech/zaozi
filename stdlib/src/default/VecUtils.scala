// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.stdlib.default

import me.jiuyang.stdlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

given VecUtilsApi with
  extension [E <: Data, V <: Vec[E], R <: Referable[V]](ref: R)
    def toSeq(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext,
      VecApi[E, V]
    ): IndexedSeq[Ref[E]] =
      new IndexedSeq[Ref[E]]:
        def length:          Int    = ref.length
        def apply(idx: Int): Ref[E] = ref.ref(idx)

  extension [E <: Connectable](seq: Seq[Referable[E]])
    def toVec(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext,
      ConstructorApi
    ): Node[Vec[E]] =
      if seq.isEmpty then throw new IllegalArgumentException("toVec requires a non-empty Seq")
      val vecWire = Wire(Vec(seq.length, seq.head.getType))
      seq.zipWithIndex.foreach: (e, i) =>
        vecWire(i) :<= e
      Node(vecWire)
