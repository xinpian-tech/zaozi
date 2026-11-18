// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*

given DialectRegistryApi with
  inline def registryCreate(
  )(
    using zone: Zone
  ): DialectRegistry = DialectRegistry(
    nativeResult[MlirStructs.MlirDialectRegistry](out => mlirApi.mlirDialectRegistryCreate()(out))
  )

  extension (dialectRegistry: DialectRegistry)
    inline def destroy(): Unit = mlirApi.mlirDialectRegistryDestroy(dialectRegistry.native)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirDialectRegistry].toInt
end given
