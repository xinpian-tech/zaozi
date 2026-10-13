// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.valuetpe

import me.jiuyang.zaozi.sv.{ConstExpr, TypeImpl}
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}
import java.lang.foreign.Arena

trait Bits extends Data:
  private[zaozi] val _width: ConstExpr
  private[sv] def rawType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
