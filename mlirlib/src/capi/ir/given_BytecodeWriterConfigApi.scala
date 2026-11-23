// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*

given BytecodeWriterConfigApi with
  inline def bytecodeWriterConfigCreate(
  )(
    using zone: Zone
  ) = BytecodeWriterConfig(
    nativeResult[MlirStructs.MlirBytecodeWriterConfig](out => mlirApi.mlirBytecodeWriterConfigCreate()(out))
  )
  extension (bytecodeWriterConfig: BytecodeWriterConfig)
    inline def destroy():                         Unit = mlirApi.mlirBytecodeWriterConfigDestroy(bytecodeWriterConfig.native)
    inline def desiredEmitVersion(version: Long): Unit =
      mlirApi.mlirBytecodeWriterConfigDesiredEmitVersion(bytecodeWriterConfig.native, version)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirBytecodeWriterConfig].toInt
end given
