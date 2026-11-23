// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.ltltpe

import me.jiuyang.zaozi.*

import org.llvm.mlir.scalalib.capi.ir.{Context, Type, Value}

import scala.scalanative.unsafe.Zone

/** A clock-less immediate boolean expression. */
trait Immediate:
  private[zaozi] val _refer: Value

  def refer(
    using Zone,
    TypeImpl
  ): Value = this.referImpl

  def toMlirType(
    using Zone,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
