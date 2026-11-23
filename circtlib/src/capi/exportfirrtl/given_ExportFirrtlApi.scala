// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.exportfirrtl

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Module, given}

given ExportFirrtlApi with
  extension (module: Module)
    inline def exportFIRRTL(
      callback:   String => Unit
    )(
      using zone: Zone
    ): LogicalResult = LogicalResult(
      callback.stringToStringCallback.use { (fn, data) =>
        nativeResult[MlirStructs.MlirLogicalResult](out => circtApi.mlirExportFIRRTL(module.native, fn, data)(out))
      }
    )
end given
