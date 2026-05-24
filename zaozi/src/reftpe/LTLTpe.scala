// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Operation, Type, Value}
import java.lang.foreign.Arena

trait LTLTPE extends HasOperation:
  private[zaozi] val _operation: Operation

  def operation(
    using TypeImpl
  ): Operation

  def refer(
    using Arena,
    TypeImpl
  ): Value

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type
