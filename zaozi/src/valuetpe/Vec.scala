// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

trait Vec[E <: Data] extends Data:
  private[zaozi] val _elementType: E
  private[zaozi] val _count:       Int

  def elementType = _elementType

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
