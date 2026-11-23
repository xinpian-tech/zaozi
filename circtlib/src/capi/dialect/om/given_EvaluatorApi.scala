// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package org.llvm.circt.scalalib.capi.dialect.om

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.structs as CirctStructs
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Location, Module, Type, given}

given EvaluatorApi with
  inline def basePathGetEmpty(
    using zone: Zone,
    context:    Context
  ): OMEvaluatorValue = OMEvaluatorValue(
    nativeResult[CirctStructs.OMEvaluatorValue](out => circtApi.omEvaluatorBasePathGetEmpty(context.native)(out))
  )

  extension (evaluator: OMEvaluator)
    inline def getModule(
      using zone: Zone
    ): Module = new Module(
      nativeResult[MlirStructs.MlirModule](out => circtApi.omEvaluatorGetModule(evaluator.native)(out))
    )
    inline def instantiate(
      className:    String,
      actualParams: OMEvaluatorValue*
    )(
      using zone:   Zone,
      context:      Context
    ): OMEvaluatorValue =
      OMEvaluatorValue(
        nativeResult[CirctStructs.OMEvaluatorValue](out =>
          circtApi.omEvaluatorInstantiate(
            evaluator.native,
            className.stringAttrGet.native,
            actualParams.length.toSize,
            actualParams.toMlirArray
          )(out)
        )
      )

  extension (evaluatorValue: OMEvaluatorValue)
    inline def listGetElement(
      pos:        Int
    )(
      using zone: Zone
    ): OMEvaluatorValue = OMEvaluatorValue(
      nativeResult[CirctStructs.OMEvaluatorValue](out =>
        circtApi.omEvaluatorListGetElement(evaluatorValue.native, pos.toSize)(out)
      )
    )
    inline def listGetNumElements: Int = circtApi.omEvaluatorListGetNumElements(evaluatorValue.native).toLong.toInt
  inline def evaluatorNew(
    module:     Module
  )(
    using zone: Zone
  ): OMEvaluator = OMEvaluator(
    nativeResult[CirctStructs.OMEvaluator](out => circtApi.omEvaluatorNew(module.native)(out))
  )
  extension (omObject:       OMEvaluatorValue)
    inline def objectGetField(
      name:       String
    )(
      using zone: Zone,
      context:    Context
    ): OMEvaluatorValue =
      OMEvaluatorValue(
        nativeResult[CirctStructs.OMEvaluatorValue](out =>
          circtApi.omEvaluatorObjectGetField(omObject.native, name.stringAttrGet.native)(out)
        )
      )
    inline def objectGetFieldNames(
      using zone: Zone
    ): Attribute = new Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => circtApi.omEvaluatorObjectGetFieldNames(omObject.native)(out))
    )
    inline def objectGetHash:                       Int     = circtApi.omEvaluatorObjectGetHash(omObject.native).toInt
    inline def objectGetType(
      using zone: Zone
    ): Type = new Type(
      nativeResult[MlirStructs.MlirType](out => circtApi.omEvaluatorObjectGetType(omObject.native)(out))
    )
    inline def objectIsEq(other: OMEvaluatorValue): Boolean =
      circtApi.omEvaluatorObjectIsEq(omObject.native, other.native)
    inline def objectIsNull:                        Boolean = circtApi.omEvaluatorObjectIsNull(omObject.native)
  extension (evaluatorValue: OMEvaluatorValue)
    inline def pathGetAsString(
      using zone: Zone
    ): Attribute = new Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => circtApi.omEvaluatorPathGetAsString(evaluatorValue.native)(out))
    )
  inline def fromPrimitive(
    primitive:  Attribute
  )(
    using zone: Zone
  ): OMEvaluatorValue = OMEvaluatorValue(
    nativeResult[CirctStructs.OMEvaluatorValue](out => circtApi.omEvaluatorValueFromPrimitive(primitive.native)(out))
  )
  extension (primitive:      Attribute)
    /** wrapper to [[fromPrimitive]] */
    inline def toEvaluatorValue(
      using zone: Zone
    ): OMEvaluatorValue = fromPrimitive(primitive)
  extension (evaluatorValue: OMEvaluatorValue)
    inline def getContext(
      using zone: Zone
    ): Context = Context(
      nativeResult[MlirStructs.MlirContext](out => circtApi.omEvaluatorValueGetContext(evaluatorValue.native)(out))
    )
    inline def getLoc(
      using zone: Zone
    ): Location = Location(
      nativeResult[MlirStructs.MlirLocation](out => circtApi.omEvaluatorValueGetLoc(evaluatorValue.native)(out))
    )
    inline def getPrimitive(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => circtApi.omEvaluatorValueGetPrimitive(evaluatorValue.native)(out))
    )
    inline def isBasePath:  Boolean = circtApi.omEvaluatorValueIsABasePath(evaluatorValue.native)
    inline def isList:      Boolean = circtApi.omEvaluatorValueIsAList(evaluatorValue.native)
    inline def isObject:    Boolean = circtApi.omEvaluatorValueIsAObject(evaluatorValue.native)
    inline def isPath:      Boolean = circtApi.omEvaluatorValueIsAPath(evaluatorValue.native)
    inline def isPrimitive: Boolean = circtApi.omEvaluatorValueIsAPrimitive(evaluatorValue.native)
    inline def isNull:      Boolean = circtApi.omEvaluatorValueIsNull(evaluatorValue.native)
end given
