// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.sv

import org.llvm.circt.MlirStringRef
import org.llvm.circt.CAPI.{
  svAttrIsASVAttributeAttr,
  svSVAttributeAttrGet,
  svSVAttributeAttrGetEmitAsComment,
  svSVAttributeAttrGetExpression,
  svSVAttributeAttrGetName
}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, given}
import org.llvm.mlir.scalalib.capi.support.{StringRef, given}
import java.lang.foreign.{Arena, MemorySegment}

given AttributeApi with
  inline def getSVAttribute(
    name:          String,
    expression:    Option[String],
    emitAsComment: Boolean
  )(
    using arena:   Arena,
    context:       Context
  ): Attribute =
    val expr = expression match
      case Some(value) => value.toStringRef.segment
      case None        =>
        val empty = MlirStringRef.allocate(arena)
        MlirStringRef.data(empty, MemorySegment.NULL)
        MlirStringRef.length(empty, 0L)
        empty
    Attribute(svSVAttributeAttrGet(arena, context.segment, name.toStringRef.segment, expr, emitAsComment))
  extension (attribute: Attribute)
    inline def isSVAttribute:               Boolean = svAttrIsASVAttributeAttr(attribute.segment)
    inline def getSVAttributeName(
      using arena: Arena
    ): String = StringRef(svSVAttributeAttrGetName(arena, attribute.segment)).toScalaString
    inline def getSVAttributeExpression(
      using arena: Arena
    ): Option[String] =
      val raw = svSVAttributeAttrGetExpression(arena, attribute.segment)
      Option.when(MlirStringRef.data(raw) != MemorySegment.NULL)(StringRef(raw).toScalaString)
    inline def getSVAttributeEmitAsComment: Boolean = svSVAttributeAttrGetEmitAsComment(attribute.segment)
end given
