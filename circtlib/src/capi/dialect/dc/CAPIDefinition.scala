// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/DC.h
package org.llvm.circt.scalalib.capi.dialect.dc

import org.llvm.mlir.scalalib.capi.ir.Context

import scala.scalanative.unsafe.Zone

//
/** DC Dialect API
  * {{{
  * mlirGetDialectHandle__dc__
  * registerDCPasses
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ):                  Unit
  def registerPasses: Unit
end DialectApi
