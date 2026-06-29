// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.verif

import org.llvm.circt.CAPI.{mlirGetDialectHandle__verif__ as mlirGetDialectHandle, registerVerifPasses as r}
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
  def registerPasses: Unit = r()
end given
