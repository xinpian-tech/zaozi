// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/Sim.h
package org.llvm.circt.scalalib.capi.dialect.sim

import org.llvm.mlir.scalalib.capi.ir.{Context, Module, Type}
import org.llvm.mlir.scalalib.capi.support.LogicalResult

import java.lang.foreign.Arena

/** Sim Dialect Api
  * {{{
  * mlirGetDialectHandle__sim__
  * registerSimPasses
  * }}}
  *
  * Sim.h has no type or attribute constructors, so [[TypeApi]] builds sim types by textual parsing.
  */
trait DialectApi:
  inline def loadDialect(
    using arena: Arena,
    context:     Context
  ):                  Unit
  def registerPasses: Unit

  extension (module: Module)
    def exportDPIInterface(
      dutModule: String,
      callback:  String => Unit
    )(
      using Arena
    ): LogicalResult
end DialectApi

/** Constructors for the sim dialect types this binding covers. */
trait TypeApi:
  /** `!sim.fstring` — a format string fragment or concatenation thereof. */
  def formatStringTypeGet(
    using Arena,
    Context
  ): Type

  /** `!sim.output_stream` — a console or file output stream handle. */
  def outputStreamTypeGet(
    using Arena,
    Context
  ): Type
end TypeApi
