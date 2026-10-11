// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/Sim.h
package org.llvm.circt.scalalib.capi.dialect.sim

import org.llvm.mlir.scalalib.capi.ir.{Context, Module, Type}
import org.llvm.mlir.scalalib.capi.support.{HasSegment, HasSizeOf, LogicalResult}

import java.lang.foreign.{Arena, MemorySegment}

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
    /** Exports DPI interface JSON through CIRCT's callback-based C API. */
    inline def exportDPIInterface(
      callback: String => Unit
    )(
      using arena: Arena
    ): LogicalResult
end DialectApi

enum DPIDirection(val cValue: Int):
  case In     extends DPIDirection(0)
  case Out    extends DPIDirection(1)
  case InOut  extends DPIDirection(2)
  case Return extends DPIDirection(3)
  case Ref    extends DPIDirection(4)

/** A view of one native DPI argument, owned by its allocating arena. */
class DPIArgument(val _segment: MemorySegment)
trait DPIArgumentApi extends HasSegment[DPIArgument] with HasSizeOf[DPIArgument]:
  inline def createDPIArgument(
    name:      String,
    tpe:       Type,
    direction: DPIDirection
  )(
    using arena: Arena
  ): DPIArgument

  extension (argument: DPIArgument)
    inline def name:      String
    inline def tpe:       Type
    inline def direction: DPIDirection
end DPIArgumentApi

/** Construction and inspection of sim dialect types. */
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

  extension (tpe: Type)
    def dpiFunctionTypeGetNumArguments: Int
    def dpiFunctionTypeGetArgument(
      index: Int
    )(
      using Arena
    ): DPIArgument
    def dpiFunctionTypeGetFunctionType(
      using Arena
    ): Type

  /** `!sim.output_stream` — a console or file output stream handle. */
  def outputStreamTypeGet(
    using Arena,
    Context
  ): Type
end TypeApi
