// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.ltltpe

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.{HasOperation, Referable}
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.capi.dialect.firrtl.FirrtlEventControl
import org.llvm.mlir.scalalib.capi.ir.{Context, Operation, Type, Value}

import java.lang.foreign.Arena

case class ClockEvent(edge: FirrtlEventControl, clock: Referable[Clock] & HasOperation):
  def apply[T](
    body: ClockEvent ?=> T
  ): T = body(
    using this
  )

/** The SVA sequence, the inner bool indicate: match success or match failed. */
trait Sequence extends HasOperation:
  private[zaozi] val _operation:  Operation
  private[zaozi] val _clockevent: ClockEvent

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
