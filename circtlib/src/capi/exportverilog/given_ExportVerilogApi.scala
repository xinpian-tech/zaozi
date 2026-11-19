// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.exportverilog

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Module, given}

given ExportVerilogApi with
  extension (module: Module)
    inline def exportVerilog(
      callback:   String => Unit
    )(
      using zone: Zone
    ): LogicalResult =
      LogicalResult(
        callback.stringToStringCallback.use { (fn, data) =>
          nativeResult[MlirStructs.MlirLogicalResult](out => circtApi.mlirExportVerilog(module.native, fn, data)(out))
        }
      )
    inline def exportSplitVerilog(
      directory:  String
    )(
      using zone: Zone
    ): LogicalResult =
      LogicalResult(
        nativeResult[MlirStructs.MlirLogicalResult](out =>
          circtApi.mlirExportSplitVerilog(module.native, directory.toStringRef.native)(out)
        )
      )
end given
