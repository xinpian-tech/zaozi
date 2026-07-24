// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.TypeImpl
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.Value

import java.lang.foreign.Arena

private[zaozi] final class ContractResult[T <: Data](
  tpe:      T,
  refValue: Value)
    extends Referable[T]:
  private[zaozi] val _tpe:   T     = tpe
  private[zaozi] val _refer: Value = refValue

  def refer(
    using Arena,
    TypeImpl
  ): Value = _refer
