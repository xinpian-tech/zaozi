// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.sim

import org.llvm.circt.CAPI.{
  simAssocArrayTypeGet,
  simDPIFunctionTypeGet,
  simDynamicStringTypeGet,
  simFormatStringTypeGet,
  simOutputStreamTypeGet,
  simQueueTypeGet
}
import org.llvm.mlir.scalalib.capi.ir.{Context, Type, given}
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

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
      else arena.allocate(org.llvm.circt.SimDPIArgument.sizeof() * arguments.size)
    arguments.zipWithIndex.foreach { (arg, index) =>
      val entry = buffer.asSlice(org.llvm.circt.SimDPIArgument.sizeof() * index)
      org.llvm.circt.SimDPIArgument.name(entry, arg.name.toStringRef.segment)
      org.llvm.circt.SimDPIArgument.`type`(entry, arg.tpe.segment)
      org.llvm.circt.SimDPIArgument.direction(entry, arg.direction.cValue)
    }
    Type(simDPIFunctionTypeGet(arena, context.segment, arguments.size.toLong, buffer))

  def outputStreamTypeGet(
    using Arena,
    Context
  ): Type = Type(simOutputStreamTypeGet(summon[Arena], summon[Context].segment))
end given
