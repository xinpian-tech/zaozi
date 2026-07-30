// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.sim

import org.llvm.circt.CAPI.mlirGetDialectHandle__sim__ as mlirGetDialectHandle
import org.llvm.mlir.scalalib.capi.ir.{Context, DialectHandle, given}

import java.lang.foreign.Arena

given DialectApi with
  inline def loadDialect(
    using arena: Arena,
    context:     Context
  ): Unit =
    DialectHandle(mlirGetDialectHandle(arena)).loadDialect(
      using arena,
      context
    )
end given
