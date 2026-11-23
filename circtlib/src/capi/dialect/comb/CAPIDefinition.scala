// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/Comb.h
package org.llvm.circt.scalalib.capi.dialect.comb

import org.llvm.mlir.scalalib.capi.ir.Context

import scala.scalanative.unsafe.Zone

/** Comb Dialect API
  * {{{
  * mlirGetDialectHandle__comb__
  * registerCombPasses
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ):                  Unit
  def registerPasses: Unit
end DialectApi
