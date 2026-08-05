// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.circt.*
import org.llvm.circt.CAPI.{
  firrtlEmitInvalidate,
  firrtlImportAnnotationsFromJSONRaw,
  firrtlTypesAreEquivalent,
  firrtlValueFoldFlow
}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{
  Attribute,
  AttributeApi as MlirAttributeApi,
  Block,
  Context,
  Location,
  Type,
  Value,
  given
}

import java.lang.foreign.Arena

given UtilityApi with
  inline def emitInvalidate(block: Block, loc: Location, value: Value):             Unit            =
    firrtlEmitInvalidate(block.segment, loc.segment, value.segment)
  inline def importAnnotationsFromJSONRaw(
    annotationsStr: String
  )(
    using arena:    Arena,
    context:        Context
  ): Attribute =
    val attribute = summon[MlirAttributeApi].allocateAttribute
    firrtlImportAnnotationsFromJSONRaw(
      context.segment,
      annotationsStr.toStringRef.segment,
      attribute.segment
    )
    attribute
  inline def typesAreEquivalent(dest: Type, src: Type, requireSameWidths: Boolean): Boolean         =
    firrtlTypesAreEquivalent(dest.segment, src.segment, requireSameWidths)
  inline def valueFoldFlow(value: Value, flow: FirrtlValueFlow):                    FirrtlValueFlow =
    firrtlValueFoldFlow(value.segment, flow.toNative).fromNative
end given
