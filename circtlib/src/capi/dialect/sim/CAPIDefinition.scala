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
  * Sim.h provides constructors for all sim dialect types.
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

enum DPIDirection(val cValue: Int):
  case In     extends DPIDirection(0)
  case Out    extends DPIDirection(1)
  case InOut  extends DPIDirection(2)
  case Return extends DPIDirection(3)
  case Ref    extends DPIDirection(4)

final case class DPIArgument(name: String, tpe: Type, direction: DPIDirection)

/** Constructors for the sim dialect types. */
trait TypeApi:
  /** `!sim.fstring` — a format string fragment or concatenation thereof. */
  def formatStringTypeGet(
    using Arena,
    Context
  ): Type

  def dynamicStringTypeGet(
    using Arena,
    Context
  ): Type
  def queueTypeGet(
    element: Type,
    bound:   Int
  )(
    using Arena
  ): Type
  def assocArrayTypeGet(
    element: Type,
    index:   Type
  )(
    using Arena
  ): Type
  def dpiFunctionTypeGet(
    arguments: Seq[DPIArgument]
  )(
    using Arena,
    Context
  ): Type

  /** `!sim.output_stream` — a console or file output stream handle. */
  def outputStreamTypeGet(
    using Arena,
    Context
  ): Type
end TypeApi
