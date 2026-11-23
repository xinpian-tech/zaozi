// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package org.llvm.mlir.scalalib.capi.dialect.smt

import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type, given}

import scala.scalanative.unsafe.Zone

trait DialectApi:
  inline def loadDialect(
  )(
    using zone: Zone,
    context:    Context
  ): Unit
end DialectApi

trait TypeApi:
  inline def getArray(
    domainType: Type,
    rangeType:  Type
  )(
    using zone: Zone,
    context:    Context
  ): Type
  inline def getBitVector(
    width:      Int
  )(
    using zone: Zone,
    context:    Context
  ): Type
  inline def getBool(
    using zone: Zone,
    context:    Context
  ): Type
  inline def getInt(
    using zone: Zone,
    context:    Context
  ): Type
  inline def getSMTFunc(
    domainTypes: Seq[Type],
    rangeType:   Type
  )(
    using zone:  Zone,
    context:     Context
  ): Type
  inline def getSort(
    identifier: String,
    sortParams: Seq[Type]
  )(
    using zone: Zone,
    context:    Context
  ): Type
  extension (tpe: Type)
    inline def isAnyNonFuncSMTValueType: Boolean
    inline def isAnySMTValueType:        Boolean
    inline def isArray:                  Boolean
    inline def isBitVector:              Boolean
    inline def isBool:                   Boolean
    inline def isInt:                    Boolean
    inline def isSMTFunc:                Boolean
    inline def isSort:                   Boolean
end TypeApi

trait AttributeApi:
  extension (str:   String)
    inline def getBVCmpPredicateAttribute(
      using zone: Zone,
      context:    Context
    ): Attribute
    inline def getIntPredicateAttribute(
      using zone: Zone,
      context:    Context
    ): Attribute
  extension (value: Int)
    inline def getBitVectorAttribute(
      width:      Int
    )(
      using zone: Zone,
      context:    Context
    ):                                                    Attribute
  extension (attr:  Attribute) inline def isSMTAttribute: Boolean
  extension (str:   String)
    inline def checkBVCmpPredicateAttribute(
      using zone: Zone,
      context:    Context
    ): Boolean
    inline def checkIntPredicateAttribute(
      using zone: Zone,
      context:    Context
    ): Boolean
end AttributeApi
