// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>

// circt-c/Dialect/OM.h
package org.llvm.circt.scalalib.capi.dialect.om

import org.llvm.circt.raw.structs as CirctStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Identifier, Location, Module, Type, given}

class OMEvaluator(handle: Ptr[CirctStructs.OMEvaluator]) extends NativeHandle(handle)
trait OMEvaluatorApi                                     extends HasSizeOf[OMEvaluator]

class OMEvaluatorValue(handle: Ptr[CirctStructs.OMEvaluatorValue]) extends NativeHandle(handle)
trait OMEvaluatorValueApi                                          extends HasSizeOf[OMEvaluatorValue]

/** OM Dialect Api
  * {{{
  * mlirGetDialectHandle__om__
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
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
    using zone: Zone
  ):   TypeID
  extension (tpe: Type)
    inline def classTypeGetName(
      using zone: Zone
    ): Identifier
  inline def classTypeGetTypeID(
    using zone: Zone
  ):   TypeID
  inline def frozenBasePathTypeGetTypeID(
    using zone: Zone
  ):   TypeID
  inline def frozenPathTypeGetTypeID(
    using zone: Zone
  ):   TypeID
  extension (tpe: Type)
    inline def listTypeGetElementType(
      using zone: Zone
    ): Type
  inline def listTypeGetTypeID(
    using zone: Zone
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
  * }}}
  */
trait EvaluatorApi:
  inline def basePathGetEmpty(
    using zone: Zone,
    context:    Context
  ): OMEvaluatorValue

  extension (evaluator: OMEvaluator)
    inline def getModule(
      using zone: Zone
    ): Module
    inline def instantiate(
      className:    String,
      actualParams: OMEvaluatorValue*
    )(
      using zone:   Zone,
      context:      Context
    ): OMEvaluatorValue

  extension (evaluatorValue: OMEvaluatorValue)
    inline def listGetElement(
      pos:        Int
    )(
      using zone: Zone
    ):                             OMEvaluatorValue
    inline def listGetNumElements: Int
  inline def evaluatorNew(
    module:     Module
  )(
    using zone: Zone
  ):   OMEvaluator
  extension (omObject:       OMEvaluatorValue)
    inline def objectGetField(
      name:       String
    )(
      using zone: Zone,
      context:    Context
    ):                                              OMEvaluatorValue
    inline def objectGetFieldNames(
      using zone: Zone
    ):                                              Attribute
    inline def objectGetHash:                       Int
    inline def objectGetType(
      using zone: Zone
    ):                                              Type
    inline def objectIsEq(other: OMEvaluatorValue): Boolean
    inline def objectIsNull:                        Boolean
  extension (evaluatorValue: OMEvaluatorValue)
    inline def pathGetAsString(
      using zone: Zone
    ): Attribute
  inline def fromPrimitive(
    primitive:  Attribute
  )(
    using zone: Zone
  ):   OMEvaluatorValue
  extension (primitive:      Attribute)
    /** wrapper to [[fromPrimitive]] */
    inline def toEvaluatorValue(
      using zone: Zone
    ): OMEvaluatorValue
  extension (evaluatorValue: OMEvaluatorValue)
    inline def getContext(
      using zone: Zone
    ):                      Context
    inline def getLoc(
      using zone: Zone
    ):                      Location
    inline def getPrimitive(
      using zone: Zone
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
      using zone: Zone
    ):                                 Attribute
    inline def integerAttrGetInt(
      using zone: Zone
    ):                                 Attribute
    inline def integerAttrToString(
      using zone: Zone
    ):                                 String
    inline def listAttrGetElement(
      pos:        Int
    )(
      using zone: Zone
    ):                                 Attribute
    inline def listAttrGetNumElements: Int
    inline def referenceAttrGetInnerRef(
      using zone: Zone
    ):                                 Attribute
end AttributeApi
