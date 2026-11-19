// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.structs as CirctStructs
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, DialectHandle, Type, given}

given TypeApi with
  extension (width:                    Int)
    inline def getAnalog(
      using zone: Zone,
      context:    Context
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetAnalog(context.native, width)(out)))
  inline def getAnyRef(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetAnyRef(context.native)(out)))
  extension (tpe:                      Type)
    inline def getBitWidth(ignoreFlip: Boolean): Long = circtApi.firrtlTypeGetBitWidth(tpe.native, ignoreFlip).toLong
  inline def getBoolean(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetBoolean(context.native)(out)))
  extension (firrtlBundleFields:       Seq[FirrtlBundleField])
    inline def getBundle(
      using zone: Zone,
      context:    Context
    ): Type =
      val buffer = firrtlBundleFields.toMlirArray
      Type(
        nativeResult[MlirStructs.MlirType](out =>
          circtApi.firrtlTypeGetBundle(context.native, firrtlBundleFields.size.toUSize, buffer)(out)
        )
      )
  extension (tpe:                      Type)
    inline def getBundleFieldByIndex(
      idx:        Int
    )(
      using zone: Zone
    ): FirrtlBundleField =
      val buffer = alloc[CirctStructs.FIRRTLBundleField]()
      require(
        circtApi.firrtlTypeGetBundleFieldByIndex(tpe.native, idx.toUSize, buffer),
        s"unknown bundle field index $idx"
      )
      FirrtlBundleField(buffer)
    inline def getBundleFieldIndex(
      fieldName:  String
    )(
      using zone: Zone
    ): Int =
      circtApi.firrtlTypeGetBundleFieldIndex(tpe.native, fieldName.toStringRef.native).toInt
    inline def getBundleNumFields: Long = circtApi.firrtlTypeGetBundleNumFields(tpe.native).toLong
  inline def getClassTpe(
    name:                String,
    firrtlClassElements: Seq[FirrtlClassElement]
  )(
    using zone:          Zone,
    context:             Context
  ): Type =
    val buffer = firrtlClassElements.toMlirArray
    Type(
      nativeResult[MlirStructs.MlirType](out =>
        circtApi.firrtlTypeGetClass(
          context.native,
          name.flatSymbolRefAttrGet.native,
          firrtlClassElements.size.toUSize,
          buffer
        )(out)
      )
    )
  inline def getClock(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetClock(context.native)(out)))
  extension (tpe:                      Type)
    inline def getConstType(
      using zone: Zone
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetConstType(tpe.native, true)(out)))
  inline def getDouble(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetDouble(context.native)(out)))
  inline def getInteger(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetInteger(context.native)(out)))
  inline def getList(
    element:    Type
  )(
    using zone: Zone,
    context:    Context
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetList(context.native, element.native)(out))
  )
  inline def getMaskType(
    tpe:        Type
  )(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetMaskType(tpe.native)(out)))
  inline def getPath(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetPath(context.native)(out)))
  extension (tpe:                      Type)
    inline def getRef(
      forceable:  Boolean
    )(
      using zone: Zone,
      context:    Context
    ): Type =
      Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetRef(tpe.native, forceable)(out)))
    inline def getRef(
      forceable:  Boolean,
      layers:     Seq[String]
    )(
      using zone: Zone,
      context:    Context
    ): Type =
      val layersAttr =
        if (layers.isEmpty) throw new IllegalArgumentException("Layer sequence cannot be empty")
        else
          layers match
            case Seq(root)    => root.flatSymbolRefAttrGet
            case root +: rest => root.symbolRefAttrGet(rest.map(_.flatSymbolRefAttrGet))
      Type(
        nativeResult[MlirStructs.MlirType](out =>
          circtApi.firrtlTypeGetColoredRef(tpe.native, forceable, layersAttr.native)(out)
        )
      )
  inline def getReset(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetReset(context.native)(out)))
  extension (width:                    Int)
    inline def getSInt(
      using zone: Zone,
      context:    Context
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetSInt(context.native, width)(out)))
  inline def getString(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetString(context.native)(out)))
  extension (width:                    Int)
    inline def getUInt(
      using zone: Zone,
      context:    Context
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetUInt(context.native, width)(out)))
  extension (tpe:                      Type)
    inline def getVector(
      count:      Int
    )(
      using zone: Zone,
      context:    Context
    ): Type = Type(
      nativeResult[MlirStructs.MlirType](out =>
        circtApi.firrtlTypeGetVector(context.native, tpe.native, count.toUSize)(out)
      )
    )
    inline def getVectorElementType(
      using zone: Zone
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.firrtlTypeGetVectorElement(tpe.native)(out)))
    inline def getVectorElementNum: Long = circtApi.firrtlTypeGetVectorNumElements(tpe.native).toLong
  extension (tpe:                      Type)
    inline def isAnalog:     Boolean = circtApi.firrtlTypeIsAAnalog(tpe.native)
    inline def isAnyRef:     Boolean = circtApi.firrtlTypeIsAAnyRef(tpe.native)
    inline def isBoolean:    Boolean = circtApi.firrtlTypeIsABoolean(tpe.native)
    inline def isBundle:     Boolean = circtApi.firrtlTypeIsABundle(tpe.native)
    inline def isClass:      Boolean = circtApi.firrtlTypeIsAClass(tpe.native)
    inline def isClock:      Boolean = circtApi.firrtlTypeIsAClock(tpe.native)
    inline def isDouble:     Boolean = circtApi.firrtlTypeIsADouble(tpe.native)
    inline def isInteger:    Boolean = circtApi.firrtlTypeIsAInteger(tpe.native)
    inline def isList:       Boolean = circtApi.firrtlTypeIsAList(tpe.native)
    inline def isOpenBundle: Boolean = circtApi.firrtlTypeIsAOpenBundle(tpe.native)
    inline def isPath:       Boolean = circtApi.firrtlTypeIsAPath(tpe.native)
    inline def isRef:        Boolean = circtApi.firrtlTypeIsARef(tpe.native)
    inline def isReset:      Boolean = circtApi.firrtlTypeIsAReset(tpe.native)
    inline def isSInt:       Boolean = circtApi.firrtlTypeIsASInt(tpe.native)
    inline def isString:     Boolean = circtApi.firrtlTypeIsAString(tpe.native)
    inline def isUInt:       Boolean = circtApi.firrtlTypeIsAUInt(tpe.native)
    inline def isVector:     Boolean = circtApi.firrtlTypeIsAVector(tpe.native)
    inline def isConst:      Boolean = circtApi.firrtlTypeIsConst(tpe.native)
    inline def isPassive:    Boolean = circtApi.firrtlTypeIsPassive(tpe.native)
    inline def isEquivalentTo(
      that:              Type,
      requireSameWidths: Boolean
    ): Boolean = circtApi.firrtlTypesAreEquivalent(tpe.native, that.native, requireSameWidths)
end given
