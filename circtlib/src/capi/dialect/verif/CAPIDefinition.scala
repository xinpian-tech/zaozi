// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/Verif.h
package org.llvm.circt.scalalib.capi.dialect.verif

import org.llvm.mlir.scalalib.capi.ir.Context

import java.lang.foreign.Arena

/** Verif Dialect Api
  * {{{
  * mlirGetDialectHandle__verif__
  * registerVerifPasses
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using arena: Arena,
    context:     Context
  ):                  Unit
  def registerPasses: Unit
end DialectApi
