// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.ltl

import org.llvm.circt.CAPI.{ltlPropertyTypeGet, ltlSequenceTypeGet, ltlTypeIsAProperty, ltlTypeIsASequence}
import org.llvm.mlir.scalalib.capi.ir.{Context, Type, given}

import java.lang.foreign.Arena

given TypeApi with
  inline def sequenceTypeGet(
    using arena: Arena,
    context:     Context
  ): Type = Type(ltlSequenceTypeGet(arena, context.segment))
  inline def propertyTypeGet(
    using arena: Arena,
    context:     Context
  ): Type = Type(ltlPropertyTypeGet(arena, context.segment))
  extension (tpe: Type)
    inline def isSequence: Boolean = ltlTypeIsASequence(tpe.segment)
    inline def isProperty: Boolean = ltlTypeIsAProperty(tpe.segment)
end given
