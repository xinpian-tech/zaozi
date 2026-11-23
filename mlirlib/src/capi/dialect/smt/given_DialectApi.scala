// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package org.llvm.mlir.scalalib.capi.dialect.smt

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.{Context, DialectHandle, given}

given DialectApi with
  inline def loadDialect(
  )(
    using zone: Zone,
    context:    Context
  ): Unit =
    DialectHandle(nativeResult[MlirStructs.MlirDialectHandle](out => mlirApi.mlirGetDialectHandle__smt__()(out)))
      .loadDialect(
        using zone,
        context
      )
end given
