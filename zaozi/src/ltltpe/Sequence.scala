// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.ltltpe

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.Referable
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.capi.dialect.firrtl.FirrtlEventControl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type, Value}

import scala.scalanative.unsafe.Zone

case class ClockEvent(edge: FirrtlEventControl, clock: Referable[Clock]):
  def apply[T](
    body: ClockEvent ?=> T
  ): T = body(
    using this
  )

/** The SVA sequence, the inner bool indicate: match success or match failed. */
trait Sequence:
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
