// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.support

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*

given LlvmThreadPoolApi with
  inline def llvmThreadPoolCreate(
  )(
    using zone: Zone
  ): LlvmThreadPool = LlvmThreadPool(
    nativeResult[MlirStructs.MlirLlvmThreadPool](out => mlirApi.mlirLlvmThreadPoolCreate()(out))
  )
  extension (llvmThreadPool: LlvmThreadPool)
    inline def destroy(): Unit = mlirApi.mlirLlvmThreadPoolDestroy(llvmThreadPool.native)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirLlvmThreadPool].toInt
end given
