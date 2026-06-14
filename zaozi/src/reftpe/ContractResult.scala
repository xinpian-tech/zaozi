// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.TypeImpl
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Operation, Value, given}

import java.lang.foreign.Arena

private[zaozi] final class ContractResult[T <: Data](
  tpe: T,
  op:  Operation)
    extends Referable[T]
    with HasOperation:
  private[zaozi] val _tpe: T = tpe

  def refer(
    using Arena,
    TypeImpl
  ): Value = op.getResult(0)

  def operation(
    using TypeImpl
  ): Operation = op
