// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import scala.scalanative.unsafe.Zone

trait Vec[E <: Data] extends Connectable:
  private[zaozi] val _elementType: E
  private[zaozi] val _count:       Int

  def elementType = _elementType

  def count(
    using Zone,
    Context,
    TypeImpl
  ): Int = this.countImpl

  def toMlirType(
    using Zone,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
