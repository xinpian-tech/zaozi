// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.ltl

import org.llvm.circt.CAPI.{ltlAttrIsAClockEdgeAttr, ltlClockEdgeAttrGet, ltlClockEdgeAttrGetValue}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, given}

import java.lang.foreign.Arena

given AttributeApi with
  extension (edge:      LTLClockEdge)
    inline def toAttribute(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(ltlClockEdgeAttrGet(arena, context.segment, edge.toNative))
  extension (attribute: Attribute)
    inline def isClockEdgeAttr:       Boolean      = ltlAttrIsAClockEdgeAttr(attribute.segment)
    inline def clockEdgeAttrGetValue: LTLClockEdge = ltlClockEdgeAttrGetValue(attribute.segment).fromNative
end given
