// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.*
import org.llvm.mlir.CAPI.{
  mlirF64TypeGet,
  mlirIntegerTypeGet,
  mlirIntegerTypeGetWidth,
  mlirIntegerTypeSignedGet,
  mlirIntegerTypeUnsignedGet,
  mlirNoneTypeGet,
  mlirTypeEqual
}

import java.lang.foreign.{Arena, MemorySegment}

given TypeApi with
  inline def f64TypeGet(
    using arena: Arena,
    context:     Context
  ): Type = Type(mlirF64TypeGet(arena, context.segment))

  inline def noneTypeGet(
    using arena: Arena,
    context:     Context
  ) = Type(mlirNoneTypeGet(arena, context.segment))

  extension (width: Int)
    inline def integerTypeSignedGet(
      using arena: Arena,
      context:     Context
    ): Type = Type(mlirIntegerTypeSignedGet(arena, context.segment, width))
    inline def integerTypeUnsignedGet(
      using arena: Arena,
      context:     Context
    ): Type = Type(mlirIntegerTypeUnsignedGet(arena, context.segment, width))
    inline def integerTypeGet(
      using arena: Arena,
      context:     Context
    ): Type = Type(mlirIntegerTypeGet(arena, context.segment, width))
  extension (tpe:   Type)
    inline def integerTypeGetWidth: Int           =
      mlirIntegerTypeGetWidth(tpe.segment)
    inline def equal(that: Type):   Boolean       = mlirTypeEqual(tpe.segment, that.segment)
    inline def segment:             MemorySegment = tpe._segment
    inline def sizeOf:              Int           = MlirType.sizeof().toInt
end given
