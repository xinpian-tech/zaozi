// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

/** A fixed-length, homogeneous array of `E` elements (FIRRTL's vector type). Elements are accessed via `apply`/`ref`
  * with either a literal `Int` index (static, `SubindexOp`) or a `Referable[UInt]` index (dynamic, `SubaccessOp`); see
  * `me.jiuyang.zaozi.default.VecApi`.
  */
trait Vec[E <: Data] extends Connectable:
  private[zaozi] val _elementType: E
  private[zaozi] val _count:       Int

  def elementType = _elementType

  /** Number of elements, i.e. the vector's declared length. */
  def count(
    using Arena,
    Context,
    TypeImpl
  ): Int = this.countImpl

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
