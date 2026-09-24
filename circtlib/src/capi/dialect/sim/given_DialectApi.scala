// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.sim

import org.llvm.circt.CAPI.{mlirExportDPIInterface, mlirGetDialectHandle__sim__ as mlirGetDialectHandle, registerSimPasses as r}
import org.llvm.mlir.scalalib.capi.ir.{Context, DialectHandle, Module, given}
import org.llvm.mlir.scalalib.capi.support.{LogicalResult, given}

import java.lang.foreign.{Arena, MemorySegment}

given DialectApi with
  inline def loadDialect(
    using arena: Arena,
    context:     Context
  ): Unit =
    DialectHandle(mlirGetDialectHandle(arena)).loadDialect(
      using arena,
      context
    )
  def registerPasses: Unit = r()

  extension (module: Module)
    def exportDPIInterface(
      dutModule: String,
      callback:  String => Unit
    )(
      using arena: Arena
    ): LogicalResult =
      LogicalResult(
        mlirExportDPIInterface(
          arena,
          module.segment,
          dutModule.toStringRef.segment,
          callback.stringToStringCallback.segment,
          MemorySegment.NULL
        )
      )
end given
