// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.sim

import org.llvm.circt.{SimDPIArgument as NativeDPIArgument}
import org.llvm.circt.CAPI.{
  simAssocArrayTypeGet,
  simDPIFunctionTypeGet,
  simDPIFunctionTypeGetArgument,
  simDPIFunctionTypeGetFunctionType,
  simDPIFunctionTypeGetNumArguments,
  simDynamicStringTypeGet,
  simFormatStringTypeGet,
  simOutputStreamTypeGet,
  simQueueTypeGet
}
import org.llvm.mlir.scalalib.capi.ir.{Context, Type, given}
import org.llvm.mlir.scalalib.capi.support.{StringRef, given}

import java.lang.foreign.{Arena, MemorySegment}

given DPIArgumentApi with
  inline def createDPIArgument(
    name:      String,
    tpe:       Type,
    direction: DPIDirection
  )(
    using arena: Arena
  ): DPIArgument =
    val raw = NativeDPIArgument.allocate(arena)
    NativeDPIArgument.name(raw, name.toStringRef.segment)
    NativeDPIArgument.`type`(raw, tpe.segment)
    NativeDPIArgument.direction(raw, direction.cValue)
    DPIArgument(raw)

  extension (argument: DPIArgument)
    inline def segment:   MemorySegment = argument._segment
    inline def sizeOf:    Int           = NativeDPIArgument.sizeof().toInt
    inline def name:      String        = StringRef(NativeDPIArgument.name(argument.segment)).toScalaString
    inline def tpe:       Type          = Type(NativeDPIArgument.`type`(argument.segment))
    inline def direction: DPIDirection  = DPIDirection.fromOrdinal(NativeDPIArgument.direction(argument.segment))
end given

given TypeApi with
  def formatStringTypeGet(
    using Arena,
    Context
  ): Type = Type(simFormatStringTypeGet(summon[Arena], summon[Context].segment))

  def dynamicStringTypeGet(
    using arena: Arena,
    context:     Context
  ): Type =
    Type(simDynamicStringTypeGet(arena, context.segment))

  def queueTypeGet(
    element:     Type,
    bound:       Int
  )(
    using arena: Arena
  ): Type =
    require(bound >= 0, "sim queue bound must be nonnegative")
    Type(simQueueTypeGet(arena, element.segment, bound))

  def assocArrayTypeGet(
    element:     Type,
    index:       Type
  )(
    using arena: Arena
  ): Type =
    Type(simAssocArrayTypeGet(arena, element.segment, index.segment))

  def dpiFunctionTypeGet(
    arguments:   Seq[DPIArgument]
  )(
    using arena: Arena,
    context:     Context
  ): Type =
    val buffer =
      if arguments.isEmpty then MemorySegment.NULL
      else NativeDPIArgument.allocateArray(arguments.size.toLong, arena)
    arguments.zipWithIndex.foreach { (arg, index) =>
      MemorySegment.copy(arg.segment, 0L, buffer, NativeDPIArgument.sizeof() * index, NativeDPIArgument.sizeof())
    }
    Type(simDPIFunctionTypeGet(arena, context.segment, arguments.size.toLong, buffer))

  extension (tpe: Type)
    def dpiFunctionTypeGetNumArguments: Int = simDPIFunctionTypeGetNumArguments(tpe.segment).toInt

    def dpiFunctionTypeGetArgument(
      index: Int
    )(
      using arena: Arena
    ): DPIArgument = DPIArgument(simDPIFunctionTypeGetArgument(arena, tpe.segment, index.toLong))

    def dpiFunctionTypeGetFunctionType(
      using arena: Arena
    ): Type = Type(simDPIFunctionTypeGetFunctionType(arena, tpe.segment))

  def outputStreamTypeGet(
    using Arena,
    Context
  ): Type = Type(simOutputStreamTypeGet(summon[Arena], summon[Context].segment))
end given
