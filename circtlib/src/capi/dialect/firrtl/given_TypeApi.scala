// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.circt.*
import org.llvm.circt.CAPI.{
  firrtlTypeGetAnalog,
  firrtlTypeGetAnyRef,
  firrtlTypeGetBitWidth,
  firrtlTypeGetBoolean,
  firrtlTypeGetBundle,
  firrtlTypeGetBundleFieldByIndex,
  firrtlTypeGetBundleFieldIndex,
  firrtlTypeGetBundleNumFields,
  firrtlTypeGetClass,
  firrtlTypeGetClock,
  firrtlTypeGetColoredRef,
  firrtlTypeGetConstType,
  firrtlTypeGetDouble,
  firrtlTypeGetInteger,
  firrtlTypeGetList,
  firrtlTypeGetMaskType,
  firrtlTypeGetPath,
  firrtlTypeGetRef,
  firrtlTypeGetReset,
  firrtlTypeGetSInt,
  firrtlTypeGetString,
  firrtlTypeGetUInt,
  firrtlTypeGetVector,
  firrtlTypeGetVectorElement,
  firrtlTypeGetVectorNumElements,
  firrtlTypeIsAAnalog,
  firrtlTypeIsAAnyRef,
  firrtlTypeIsABoolean,
  firrtlTypeIsABundle,
  firrtlTypeIsAClass,
  firrtlTypeIsAClock,
  firrtlTypeIsADouble,
  firrtlTypeIsAInteger,
  firrtlTypeIsAList,
  firrtlTypeIsAOpenBundle,
  firrtlTypeIsAPath,
  firrtlTypeIsARef,
  firrtlTypeIsAReset,
  firrtlTypeIsASInt,
  firrtlTypeIsAString,
  firrtlTypeIsAUInt,
  firrtlTypeIsAVector,
  firrtlTypeIsConst
}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, DialectHandle, Type, given}

import java.lang.foreign.Arena

given TypeApi with
  extension (width:                    Int)
    inline def getAnalog(
      using arena: Arena,
      context:     Context
    ): Type = Type(firrtlTypeGetAnalog(arena, context.segment, width))
  inline def getAnyRef(
    using arena: Arena,
    context:     Context
  ): Type = Type(firrtlTypeGetAnyRef(arena, context.segment))
  extension (tpe:                      Type)
    inline def getBitWidth(ignoreFlip: Boolean): Long = firrtlTypeGetBitWidth(tpe.segment, ignoreFlip)
  inline def getBoolean(
    using arena: Arena,
    context:     Context
  ): Type = Type(firrtlTypeGetBoolean(arena, context.segment))
  extension (firrtlBundleFields:       Seq[FirrtlBundleField])
    inline def getBundle(
      using arena: Arena,
      context:     Context
    ): Type =
      val buffer = FIRRTLBundleField.allocateArray(firrtlBundleFields.size, arena)
      firrtlBundleFields.zipWithIndex.foreach:
        case (field, i) =>
          buffer.asSlice(field.sizeOf * i, field.sizeOf).copyFrom(field.segment)
      Type(firrtlTypeGetBundle(arena, context.segment, firrtlBundleFields.size, buffer))
  extension (tpe:                      Type)
    inline def getBundleFieldByIndex(
      idx:         Int
    )(
      using arena: Arena
    ): FirrtlBundleField =
      val buffer = FIRRTLBundleField.allocate(arena)
      firrtlTypeGetBundleFieldByIndex(tpe.segment, idx, buffer)
      FirrtlBundleField(buffer)
    inline def getBundleFieldIndex(
      fieldName:   String
    )(
      using arena: Arena
    ): Int =
      firrtlTypeGetBundleFieldIndex(tpe.segment, fieldName.toStringRef.segment)
    inline def getBundleNumFields: Long = firrtlTypeGetBundleNumFields(tpe.segment)
  inline def getClassTpe(
    name:                String,
    firrtlClassElements: Seq[FirrtlClassElement]
  )(
    using arena:         Arena,
    context:             Context
  ): Type =
    val buffer = FIRRTLClassElement.allocateArray(firrtlClassElements.size, arena)
    firrtlClassElements.zipWithIndex.foreach:
      case (element, i) =>
        buffer.asSlice(element.sizeOf * i, element.sizeOf).copyFrom(element.segment)
    Type(firrtlTypeGetClass(arena, context.segment, name.toStringRef.segment, firrtlClassElements.size, buffer))
  inline def getClock(
    using arena: Arena,
    context:     Context
  ): Type = Type(firrtlTypeGetClock(arena, context.segment))
  extension (tpe:                      Type)
    inline def getConstType(
      using arena: Arena
    ): Type = Type(firrtlTypeGetConstType(arena, tpe.segment, true))
  inline def getDouble(
    using arena: Arena,
    context:     Context
  ): Type = Type(firrtlTypeGetDouble(arena, context.segment))
  inline def getInteger(
    using arena: Arena,
    context:     Context
  ): Type = Type(firrtlTypeGetInteger(arena, context.segment))
  inline def getList(
    element:     Type
  )(
    using arena: Arena,
    context:     Context
  ): Type = Type(firrtlTypeGetList(arena, context.segment, element.segment))
  inline def getMaskType(
    using arena: Arena,
    context:     Context
  ): Type = Type(firrtlTypeGetMaskType(arena, context.segment))
  inline def getPath(
    using arena: Arena,
    context:     Context
  ): Type = Type(firrtlTypeGetPath(arena, context.segment))
  extension (tpe:                      Type)
    inline def getRef(
      forceable:   Boolean
    )(
      using arena: Arena,
      context:     Context
    ): Type =
      Type(firrtlTypeGetRef(arena, tpe.segment, forceable))
    inline def getRef(
      forceable:   Boolean,
      layers:      Seq[String]
    )(
      using arena: Arena,
      context:     Context
    ): Type =
      val layersAttr =
        if (layers.isEmpty) throw new IllegalArgumentException("Layer sequence cannot be empty")
        else
          layers match
            case Seq(root)    => root.flatSymbolRefAttrGet
            case root +: rest => root.symbolRefAttrGet(rest.map(_.flatSymbolRefAttrGet))
      Type(firrtlTypeGetColoredRef(arena, tpe.segment, forceable, layersAttr.segment))
  inline def getReset(
    using arena: Arena,
    context:     Context
  ): Type = Type(firrtlTypeGetReset(arena, context.segment))
  extension (width:                    Int)
    inline def getSInt(
      using arena: Arena,
      context:     Context
    ): Type = Type(firrtlTypeGetSInt(arena, context.segment, width))
  inline def getString(
    using arena: Arena,
    context:     Context
  ): Type = Type(firrtlTypeGetString(arena, context.segment))
  extension (width:                    Int)
    inline def getUInt(
      using arena: Arena,
      context:     Context
    ): Type = Type(firrtlTypeGetUInt(arena, context.segment, width))
  extension (tpe:                      Type)
    inline def getVector(
      count:       Int
    )(
      using arena: Arena,
      context:     Context
    ): Type = Type(firrtlTypeGetVector(arena, context.segment, tpe.segment, count))
    inline def getVectorElementType(
      using arena: Arena
    ): Type = Type(firrtlTypeGetVectorElement(arena, tpe.segment))
    inline def getVectorElementNum: Long = firrtlTypeGetVectorNumElements(tpe.segment)
  extension (tpe:                      Type)
    inline def isAnalog:     Boolean = firrtlTypeIsAAnalog(tpe.segment)
    inline def isAnyRef:     Boolean = firrtlTypeIsAAnyRef(tpe.segment)
    inline def isBoolean:    Boolean = firrtlTypeIsABoolean(tpe.segment)
    inline def isBundle:     Boolean = firrtlTypeIsABundle(tpe.segment)
    inline def isClass:      Boolean = firrtlTypeIsAClass(tpe.segment)
    inline def isClock:      Boolean = firrtlTypeIsAClock(tpe.segment)
    inline def isDouble:     Boolean = firrtlTypeIsADouble(tpe.segment)
    inline def isInteger:    Boolean = firrtlTypeIsAInteger(tpe.segment)
    inline def isList:       Boolean = firrtlTypeIsAList(tpe.segment)
    inline def isOpenBundle: Boolean = firrtlTypeIsAOpenBundle(tpe.segment)
    inline def isPath:       Boolean = firrtlTypeIsAPath(tpe.segment)
    inline def isRef:        Boolean = firrtlTypeIsARef(tpe.segment)
    inline def isReset:      Boolean = firrtlTypeIsAReset(tpe.segment)
    inline def isSInt:       Boolean = firrtlTypeIsASInt(tpe.segment)
    inline def isString:     Boolean = firrtlTypeIsAString(tpe.segment)
    inline def isUInt:       Boolean = firrtlTypeIsAUInt(tpe.segment)
    inline def isVector:     Boolean = firrtlTypeIsAVector(tpe.segment)
    inline def isConst:      Boolean = firrtlTypeIsConst(tpe.segment)
end given
