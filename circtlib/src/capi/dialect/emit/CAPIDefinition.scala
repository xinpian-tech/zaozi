// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.emit

// circt-c/Dialect/Esi.h
import org.llvm.mlir.scalalib.capi.ir.Context

import scala.scalanative.unsafe.Zone

trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ): Unit
end DialectApi
