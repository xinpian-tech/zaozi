// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.TypeImpl
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Operation, Value, given}

import java.lang.foreign.Arena

abstract class Reg[T <: Data] extends Writable[T]:
  private[zaozi] val _tpe:   T
  private[zaozi] val _refer: Value

  def operation(
    using Arena
  ): Operation = _refer.opResultGetOwner

  def refer(
    using Arena,
    TypeImpl
  ): Value = this.referImpl
