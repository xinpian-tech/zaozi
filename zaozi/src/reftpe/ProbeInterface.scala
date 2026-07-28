// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.{DVInterface, TypeImpl}
import org.llvm.mlir.scalalib.capi.ir.Value

import java.lang.foreign.Arena

abstract class ProbeInterface[T <: DVInterface[?, ?]] extends Writable[T]:
  private[zaozi] val _tpe:   T
  private[zaozi] val _refer: Value
  def refer(
    using Arena,
    TypeImpl
  ): Value = this.referImpl
