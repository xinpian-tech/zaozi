// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.exportfirrtl

import org.llvm.mlir.scalalib.capi.support.LogicalResult
import org.llvm.mlir.scalalib.capi.ir.Module

import scala.scalanative.unsafe.Zone

trait ExportFirrtlApi:
  extension (module: Module)
    inline def exportFIRRTL(
      callback:   String => Unit
    )(
      using zone: Zone
    ): LogicalResult
end ExportFirrtlApi
