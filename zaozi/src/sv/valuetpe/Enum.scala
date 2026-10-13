// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.valuetpe

import me.jiuyang.zaozi.default.bundleFieldName
import me.jiuyang.zaozi.sv.TypeImpl
import me.jiuyang.zaozi.sv.reftpe.EnumMember
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}
import java.lang.foreign.Arena

/** Members have consecutive encodings from zero, as represented by the native HW enum. */
abstract class Enum extends Data:
  private[sv] val members = collection.mutable.ArrayBuffer.empty[EnumMember[?]]
  protected def Value(
  )(
    using sourcecode.Name.Machine
  ): EnumMember[this.type] =
    val member = new EnumMember[this.type](this, bundleFieldName)
    members += member
    member
  private[sv] def rawType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
