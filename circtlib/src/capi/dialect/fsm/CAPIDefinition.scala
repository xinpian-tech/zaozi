// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/FSM.h
package org.llvm.circt.scalalib.capi.dialect.fsm

import org.llvm.mlir.scalalib.capi.ir.Context

import scala.scalanative.unsafe.Zone

/** FSM Dialect API
  * {{{
  * mlirGetDialectHandle__fsm__
  * registerFsmPasses
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ):                  Unit
  def registerPasses: Unit
end DialectApi
