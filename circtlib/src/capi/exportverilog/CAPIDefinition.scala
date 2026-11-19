// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.exportverilog

import org.llvm.mlir.scalalib.capi.support.LogicalResult
import org.llvm.mlir.scalalib.capi.ir.Module

import scala.scalanative.unsafe.Zone

trait ExportVerilogApi:
  extension (module: Module)
    inline def exportVerilog(
      callback:   String => Unit
    )(
      using zone: Zone
    ): LogicalResult
    inline def exportSplitVerilog(
      directory:  String
    )(
      using zone: Zone
    ): LogicalResult
end ExportVerilogApi
