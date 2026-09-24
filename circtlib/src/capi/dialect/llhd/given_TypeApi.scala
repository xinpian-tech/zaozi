// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.llhd

import org.llvm.circt.CAPI.{
  llhdRefTypeGet,
  llhdRefTypeGetNestedType,
  llhdTimeTypeGet,
  llhdTypeIsARefType,
  llhdTypeIsATimeType
}
import org.llvm.mlir.scalalib.capi.ir.{Context, Type, given}

import java.lang.foreign.Arena

given TypeApi with
  def timeTypeGet(
    using arena: Arena,
    context:     Context
  ): Type =
    Type(llhdTimeTypeGet(arena, context.segment))
  def refTypeGet(
    element:     Type
  )(
    using arena: Arena
  ): Type =
    Type(llhdRefTypeGet(arena, element.segment))
  extension (tpe: Type) inline def isTimeType: Boolean = llhdTypeIsATimeType(tpe.segment)
  extension (tpe: Type) inline def isRefType:  Boolean = llhdTypeIsARefType(tpe.segment)
  extension (tpe: Type)
    def refTypeGetNestedType(
      using arena: Arena
    ): Type =
      Type(llhdRefTypeGetNestedType(arena, tpe.segment))
end given
