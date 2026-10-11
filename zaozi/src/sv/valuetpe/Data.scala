// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.valuetpe

import me.jiuyang.zaozi.sv.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}
import java.lang.foreign.Arena

/** Pure declaration metadata. Native types are materialized in the caller's context. */
trait Data:
  private[sv] def rawType(
    using Arena,
    Context,
    TypeImpl
  ): Type
  final def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.materializeTypeImpl
