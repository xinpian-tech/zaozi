// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.sv.{TypeImpl, TypeQueryApi}
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.Referable
import org.llvm.circt.scalalib.capi.dialect.hw.given_TypeApi as HWType
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

given TypeQueryApi with
  extension [T <: Data](ref: Referable[T])
    def width(
      using Arena,
      Context,
      TypeImpl
    ): Int =
      val result = HWType.getBitWidth(canonicalNativeType(ref.mlirType))()
      require(result >= 0, "Native type has no concrete bit width")
      result
  extension [T <: Data](ref: Referable[PackedArray[T]])
    def count(
      using Arena,
      Context,
      TypeImpl
    ): Int =
      val result = HWType.arrayTypeGetSize(canonicalNativeType(ref.mlirType))()
      require(result >= 0, "Native array has no concrete element count")
      result

private def canonicalNativeType(
  tpe: Type
)(
  using Arena
): Type =
  if HWType.isTypeAliasType(tpe)() then HWType.typeAliasTypeGetCanonicalType(tpe)() else tpe
