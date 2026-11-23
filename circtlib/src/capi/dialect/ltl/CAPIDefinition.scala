// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/LTL.h
package org.llvm.circt.scalalib.capi.dialect.ltl

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type}

import scala.scalanative.unsafe.Zone

enum LTLClockEdge:
  case Pos
  case Neg
  case Both
end LTLClockEdge
trait LTLClockEdgeApi extends HasSizeOf[LTLClockEdge] with EnumHasToNative[LTLClockEdge]

/** LTL Dialect Api
  * {{{
  * mlirGetDialectHandle__ltl__
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ): Unit
end DialectApi

/** LTL Type API
  * {{{
  * ltlPropertyTypeGet
  * ltlSequenceTypeGet
  * ltlTypeIsAProperty
  * ltlTypeIsASequence
  * }}}
  */
trait TypeApi:
  inline def sequenceTypeGet(
    using zone: Zone,
    context:    Context
  ): Type
  inline def propertyTypeGet(
    using zone: Zone,
    context:    Context
  ): Type
  extension (tpe: Type)
    inline def isSequence: Boolean
    inline def isProperty: Boolean
end TypeApi

/** LTL Attribute API
  * {{{
  * ltlAttrIsAClockEdgeAttr
  * ltlClockEdgeAttrGet
  * ltlClockEdgeAttrGetValue
  * }}}
  */
trait AttributeApi:
  extension (edge:      LTLClockEdge)
    inline def toAttribute(
      using zone: Zone,
      context:    Context
    ):      Attribute
  extension (attribute: Attribute)
    inline def isClockEdgeAttr:       Boolean
    inline def clockEdgeAttrGetValue: LTLClockEdge
end AttributeApi
