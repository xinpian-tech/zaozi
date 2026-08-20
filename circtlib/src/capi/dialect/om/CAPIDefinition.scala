// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>

// circt-c/Dialect/OM.h
package org.llvm.circt.scalalib.capi.dialect.om

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Identifier, Location, Module, Type, given}

import java.lang.foreign.{Arena, MemorySegment}

class OMEvaluator(val _segment: MemorySegment)
trait OMEvaluatorApi extends HasSegment[OMEvaluator] with HasSizeOf[OMEvaluator]

class OMEvaluatorValue(val _segment: MemorySegment)
trait OMEvaluatorValueApi extends HasSegment[OMEvaluatorValue] with HasSizeOf[OMEvaluatorValue]

/** OM Dialect Api
  * {{{
  * mlirGetDialectHandle__om__
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using arena: Arena,
    context:     Context
  ): Unit
end DialectApi

/** OM Dialect Api
  * {{{
  * omAnyTypeGetTypeID
  * omClassTypeGetName
  * omClassTypeGetTypeID
  * omFrozenBasePathTypeGetTypeID
  * omFrozenPathTypeGetTypeID
  * omListTypeGetElementType
  * omListTypeGetTypeID
  * omTypeIsAAnyType
  * omTypeIsAClassType
  * omTypeIsAFrozenBasePathType
  * omTypeIsAFrozenPathType
  * omTypeIsAListType
  * }}}
  */
trait TypeApi:
  inline def anyTypeGetTypeID(
    using arena: Arena
  ):   TypeID
  extension (tpe: Type)
    inline def classTypeGetName(
      using arena: Arena
    ): Identifier
  inline def classTypeGetTypeID(
    using arena: Arena
  ):   TypeID
  inline def frozenBasePathTypeGetTypeID(
    using arena: Arena
  ):   TypeID
  inline def frozenPathTypeGetTypeID(
    using arena: Arena
  ):   TypeID
  extension (tpe: Type)
    inline def listTypeGetElementType(
      using arena: Arena
    ): Type
  inline def listTypeGetTypeID(
    using arena: Arena
  ):   TypeID
  extension (tpe: Type)
    inline def isAnyType:            Boolean
    inline def isClassType:          Boolean
    inline def isFrozenBasePathType: Boolean
    inline def isFrozenPathType:     Boolean
    inline def isListType:           Boolean
    inline def isStringType:         Boolean
end TypeApi

/** OM Evaluator, Object, EvaluatorValue API
  * {{{
  * omEvaluatorNew
  * omEvaluatorInstantiate
  * omEvaluatorGetModule
  * omEvaluatorObjectIsNull
  * omEvaluatorObjectGetType
  * omEvaluatorObjectGetField
  * omEvaluatorObjectGetHash
  * omEvaluatorObjectIsEq
  * omEvaluatorObjectGetFieldNames
  * omEvaluatorValueGetContext
  * omEvaluatorValueGetLoc
  * omEvaluatorValueIsNull
  * omEvaluatorValueIsAObject
  * omEvaluatorValueIsAPrimitive
  * omEvaluatorValueGetPrimitive
  * omEvaluatorValueFromPrimitive
  * omEvaluatorValueIsAList
  * omEvaluatorListGetNumElements
  * omEvaluatorListGetElement
  * omEvaluatorValueIsABasePath
  * omEvaluatorBasePathGetEmpty
  * omEvaluatorValueIsAPath
  * omEvaluatorPathGetAsString
  * omEvaluatorValueIsAReference
  * omEvaluatorValueGetReferenceValue
  * }}}
  */
trait EvaluatorApi:
  inline def basePathGetEmpty(
    using arena: Arena,
    context:     Context
  ): OMEvaluatorValue

  extension (evaluator: OMEvaluator)
    inline def getModule(
      using arena: Arena
    ): Module
    inline def instantiate(
      className:    String,
      actualParams: OMEvaluatorValue*
    )(
      using arena:  Arena,
      context:      Context
    ): OMEvaluatorValue

  extension (evaluatorValue: OMEvaluatorValue)
    inline def listGetElement(
      pos:         Int
    )(
      using arena: Arena
    ):                             OMEvaluatorValue
    inline def listGetNumElements: Int
  inline def evaluatorNew(
    module:      Module
  )(
    using arena: Arena
  ):   OMEvaluator
  extension (omObject:       OMEvaluatorValue)
    inline def objectGetField(
      name:        String
    )(
      using arena: Arena,
      context:     Context
    ):                                              OMEvaluatorValue
    inline def objectGetFieldNames(
      using arena: Arena
    ):                                              Attribute
    inline def objectGetHash:                       Int
    inline def objectGetType(
      using arena: Arena
    ):                                              Type
    inline def objectIsEq(other: OMEvaluatorValue): Boolean
    inline def objectIsNull:                        Boolean
  extension (evaluatorValue: OMEvaluatorValue)
    inline def pathGetAsString(
      using arena: Arena
    ): Attribute
  inline def fromPrimitive(
    primitive:   Attribute
  )(
    using arena: Arena
  ):   OMEvaluatorValue
  extension (primitive:      Attribute)
    /** wrapper to [[fromPrimitive]] */
    inline def toEvaluatorValue(
      using arena: Arena
    ): OMEvaluatorValue
  extension (evaluatorValue: OMEvaluatorValue)
    inline def getContext(
      using arena: Arena
    ):                      Context
    inline def getLoc(
      using arena: Arena
    ):                      Location
    inline def getPrimitive(
      using arena: Arena
    ):                      Attribute
    inline def isBasePath:  Boolean
    inline def isList:      Boolean
    inline def isObject:    Boolean
    inline def isPath:      Boolean
    inline def isPrimitive: Boolean
end EvaluatorApi

/** OM Attribute API
  * {{{
  * omAttrIsAIntegerAttr
  * omAttrIsAListAttr
  * omAttrIsAReferenceAttr
  * omIntegerAttrGet
  * omIntegerAttrGetInt
  * omIntegerAttrToString
  * omListAttrGetElement
  * omListAttrGetNumElements
  * omReferenceAttrGetInnerRef
  * }}}
  */
trait AttributeApi:
  extension (attr: Attribute)
    inline def isIntegerAttr:          Boolean
    inline def isListAttr:             Boolean
    inline def isReferenceAttr:        Boolean
    inline def integerAttrGet(
      using arena: Arena
    ):                                 Attribute
    inline def integerAttrGetInt(
      using arena: Arena
    ):                                 Attribute
    inline def integerAttrToString(
      using arena: Arena
    ):                                 String
    inline def listAttrGetElement(
      pos:         Int
    )(
      using arena: Arena
    ):                                 Attribute
    inline def listAttrGetNumElements: Int
    inline def referenceAttrGetInnerRef(
      using arena: Arena
    ):                                 Attribute
end AttributeApi
