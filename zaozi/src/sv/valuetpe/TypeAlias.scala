// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.valuetpe

import me.jiuyang.zaozi.sv.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}
import java.lang.foreign.Arena

trait TypeAlias[T <: Data] extends Data:
  private[zaozi] val _scope: String
  private[zaozi] val _name:  String
  private[zaozi] val _inner: T
  private[sv] def rawType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
