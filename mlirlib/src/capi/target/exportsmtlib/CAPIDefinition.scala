// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.target.exportsmtlib

import org.llvm.mlir.scalalib.capi.ir.{Module, Operation}
import org.llvm.mlir.scalalib.capi.support.LogicalResult

import scala.scalanative.unsafe.Zone

trait ExportSmtlibApi:
  extension (module: Module)
    inline def exportSMTLIB(
      callBack:   String => Unit
    )(
      using zone: Zone
    ): Unit
    inline def exportSMTLIB(
      callBack:   String => Unit,
      emitReset:  Boolean
    )(
      using zone: Zone
    ): LogicalResult

  extension (operation: Operation)
    inline def exportSMTLIB(
      callBack:   String => Unit
    )(
      using zone: Zone
    ): Unit
    inline def exportSMTLIB(
      callBack:   String => Unit,
      emitReset:  Boolean
    )(
      using zone: Zone
    ): LogicalResult
end ExportSmtlibApi
