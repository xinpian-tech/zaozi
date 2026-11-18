// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*

import org.llvm.mlir.raw.structs.MlirAsmState

given AsmStateApi with
  inline def asmStateCreateForOperation(
    op:         Operation,
    flags:      OpPrintingFlags
  )(
    using zone: Zone
  ): Ptr[MlirAsmState] =
    nativeResult[MlirStructs.MlirAsmState](out => mlirApi.mlirAsmStateCreateForOperation(op.native, flags.native)(out))
  inline def asmStateCreateForValue(
    value:      Value,
    flags:      OpPrintingFlags
  )(
    using zone: Zone
  ): Ptr[MlirAsmState] =
    nativeResult[MlirStructs.MlirAsmState](out => mlirApi.mlirAsmStateCreateForValue(value.native, flags.native)(out))
  extension (asmState: AsmState)
    inline def destroy(): Unit = mlirApi.mlirAsmStateDestroy(asmState.native)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirAsmState].toInt
end given
