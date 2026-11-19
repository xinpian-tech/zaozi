// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.TypeImpl
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Operation, Value, given}

import scala.scalanative.unsafe.Zone

abstract class Wire[T <: Data] extends Referable[T], Writable[T]:
  private[zaozi] val _tpe:   T
  private[zaozi] val _refer: Value

  def operation(
    using Zone
  ): Operation = _refer.opResultGetOwner
  def refer(
    using Zone,
    TypeImpl
  ): Value = this.referImpl
