// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/HWArith.h
package org.llvm.circt.scalalib.capi.dialect.hwarith

import org.llvm.mlir.scalalib.capi.ir.Context

import scala.scalanative.unsafe.Zone

/** HWArith Dialect Api
  * {{{
  * mlirGetDialectHandle__hwarith__
  * registerHWArithPasses
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ):                  Unit
  def registerPasses: Unit
end DialectApi
