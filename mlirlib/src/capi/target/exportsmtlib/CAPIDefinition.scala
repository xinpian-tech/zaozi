// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.target.exportsmtlib

import org.llvm.mlir.scalalib.capi.ir.{Module, Operation}
import org.llvm.mlir.scalalib.capi.support.LogicalResult

import java.lang.foreign.Arena

trait ExportSmtlibApi:
  extension (module: Module)
    inline def exportSMTLIB(
      callBack:    String => Unit
    )(
      using arena: Arena
    ): Unit
    inline def exportSMTLIB(
      callBack:    String => Unit,
      emitReset:   Boolean
    )(
      using arena: Arena
    ): LogicalResult

  extension (operation: Operation)
    inline def exportSMTLIB(
      callBack:    String => Unit
    )(
      using arena: Arena
    ): Unit
    inline def exportSMTLIB(
      callBack:    String => Unit,
      emitReset:   Boolean
    )(
      using arena: Arena
    ): LogicalResult
end ExportSmtlibApi
