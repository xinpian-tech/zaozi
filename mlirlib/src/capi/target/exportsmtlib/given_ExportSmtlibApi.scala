// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.target.exportsmtlib

import org.llvm.mlir.CAPI.{mlirTranslateModuleToSMTLIB, mlirTranslateOperationToSMTLIB}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Module, Operation, given}

import java.lang.foreign.{Arena, MemorySegment}

given ExportSmtlibApi with
  extension (module: Module)
    inline def exportSMTLIB(
      callback:    String => Unit
    )(
      using arena: Arena
    ): Unit = module.exportSMTLIB(callback, true)
    inline def exportSMTLIB(
      callback:    String => Unit,
      emitReset:   Boolean
    )(
      using arena: Arena
    ): LogicalResult =
      LogicalResult(
        mlirTranslateModuleToSMTLIB(
          arena,
          module.segment,
          callback.stringToStringCallback.segment,
          MemorySegment.NULL,
          false,
          false,
          emitReset
        )
      )

  extension (operation: Operation)
    inline def exportSMTLIB(
      callback:    String => Unit
    )(
      using arena: Arena
    ): Unit = operation.exportSMTLIB(callback, true)
    inline def exportSMTLIB(
      callback:    String => Unit,
      emitReset:   Boolean
    )(
      using arena: Arena
    ): LogicalResult =
      LogicalResult(
        mlirTranslateOperationToSMTLIB(
          arena,
          operation.segment,
          callback.stringToStringCallback.segment,
          MemorySegment.NULL,
          false,
          false,
          emitReset
        )
      )
end given
