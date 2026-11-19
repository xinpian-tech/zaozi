// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.target.exportsmtlib

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Module, Operation, given}

given ExportSmtlibApi with
  extension (module: Module)
    inline def exportSMTLIB(
      callback:   String => Unit
    )(
      using zone: Zone
    ): Unit = module.exportSMTLIB(callback, true)
    inline def exportSMTLIB(
      callback:   String => Unit,
      emitReset:  Boolean
    )(
      using zone: Zone
    ): LogicalResult =
      LogicalResult(
        callback.stringToStringCallback.use { (fn, data) =>
          nativeResult[MlirStructs.MlirLogicalResult](out =>
            mlirApi.mlirTranslateModuleToSMTLIB(module.native, fn, data, false, false, emitReset)(out)
          )
        }
      )

  extension (operation: Operation)
    inline def exportSMTLIB(
      callback:   String => Unit
    )(
      using zone: Zone
    ): Unit = operation.exportSMTLIB(callback, true)
    inline def exportSMTLIB(
      callback:   String => Unit,
      emitReset:  Boolean
    )(
      using zone: Zone
    ): LogicalResult =
      LogicalResult(
        callback.stringToStringCallback.use { (fn, data) =>
          nativeResult[MlirStructs.MlirLogicalResult](out =>
            mlirApi.mlirTranslateOperationToSMTLIB(operation.native, fn, data, false, false, emitReset)(out)
          )
        }
      )
end given
