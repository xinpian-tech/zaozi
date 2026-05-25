// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.ltltpe

import me.jiuyang.zaozi.*

import org.llvm.mlir.scalalib.capi.ir.{Context, Operation, Type, Value}

import java.lang.foreign.Arena

/** A clock-less immediate boolean expression. */
trait Immediate extends LTLExpr:
  def operation(
    using TypeImpl
  ): Operation = this.operationImpl

  def refer(
    using Arena,
    TypeImpl
  ): Value = this.referImpl

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
