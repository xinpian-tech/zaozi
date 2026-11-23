// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.ltl

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.enumerations as CEnums
import org.llvm.circt.raw.functions as circtApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, given}

given AttributeApi with
  extension (edge:      LTLClockEdge)
    inline def toAttribute(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        circtApi.ltlClockEdgeAttrGet(context.native, CEnums.LTLClockEdge.define(edge.toNative))(out)
      )
    )
  extension (attribute: Attribute)
    inline def isClockEdgeAttr:       Boolean      = circtApi.ltlAttrIsAClockEdgeAttr(attribute.native)
    inline def clockEdgeAttrGetValue: LTLClockEdge = circtApi.ltlClockEdgeAttrGetValue(attribute.native).int.fromNative
end given
