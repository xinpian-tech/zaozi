// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given TypeApi with
  inline def f64TypeGet(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => mlirApi.mlirF64TypeGet(context.native)(out)))

  inline def noneTypeGet(
    using zone: Zone,
    context:    Context
  ) = Type(nativeResult[MlirStructs.MlirType](out => mlirApi.mlirNoneTypeGet(context.native)(out)))

  extension (width: Int)
    inline def integerTypeSignedGet(
      using zone: Zone,
      context:    Context
    ): Type = Type(
      nativeResult[MlirStructs.MlirType](out => mlirApi.mlirIntegerTypeSignedGet(context.native, width.toUInt)(out))
    )
    inline def integerTypeUnsignedGet(
      using zone: Zone,
      context:    Context
    ): Type = Type(
      nativeResult[MlirStructs.MlirType](out => mlirApi.mlirIntegerTypeUnsignedGet(context.native, width.toUInt)(out))
    )
    inline def integerTypeGet(
      using zone: Zone,
      context:    Context
    ): Type = Type(
      nativeResult[MlirStructs.MlirType](out => mlirApi.mlirIntegerTypeGet(context.native, width.toUInt)(out))
    )
  extension (tpe:   Type)
    inline def integerTypeGetWidth: Int     =
      mlirApi.mlirIntegerTypeGetWidth(tpe.native).toInt
    inline def equal(that: Type):   Boolean = mlirApi.mlirTypeEqual(tpe.native, that.native)
    inline def print(
      callback:   String => Unit
    )(
      using zone: Zone
    ): Unit = callback.stringToStringCallback.use { (fn, data) => mlirApi.mlirTypePrint(tpe.native, fn, data) }

    inline def sizeOf: Int = sizeof[MlirStructs.MlirType].toInt
end given
