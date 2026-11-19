// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.fsm

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import org.llvm.mlir.scalalib.capi.ir.{Context, DialectHandle, given}

import scala.scalanative.unsafe.Zone

given DialectApi with
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ): Unit =
    DialectHandle(
      org.llvm.mlir.scalalib.capi.support.nativeResult[MlirStructs.MlirDialectHandle](out =>
        circtApi.mlirGetDialectHandle__fsm__()(out)
      )
    ).loadDialect(
      using zone,
      context
    )
  def registerPasses: Unit = circtApi.registerFSMPasses()
end given
