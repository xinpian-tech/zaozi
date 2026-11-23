// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given DialectApi with
  extension (dialect: Dialect)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirDialect].toInt
end given

given DialectHandleApi with
  extension (dialectHandle: DialectHandle)
    inline def getNamespace(
      using zone: Zone
    ): String = StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => mlirApi.mlirDialectHandleGetNamespace(dialectHandle.native)(out))
    ).toScalaString
    inline def loadDialect(
      using zone: Zone,
      context:    Context
    ): Unit = nativeResult[MlirStructs.MlirDialect](out =>
      mlirApi.mlirDialectHandleLoadDialect(dialectHandle.native, context.native)(out)
    )
    inline def insertDialect(dialectRegistry: DialectRegistry): Unit =
      mlirApi.mlirDialectHandleInsertDialect(dialectHandle.native, dialectRegistry.native)
    inline def insertDialect(
    )(
      using context: Context
    ): Unit =
      mlirApi.mlirDialectHandleRegisterDialect(dialectHandle.native, context.native)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirDialectHandle].toInt
end given
