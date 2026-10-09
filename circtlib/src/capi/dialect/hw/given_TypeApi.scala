// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.hw

import org.llvm.circt.CAPI.{
  hwArrayTypeGet,
  hwArrayTypeGetElementType,
  hwArrayTypeGetSize,
  hwGetBitWidth,
  hwInOutTypeGet,
  hwInOutTypeGetElementType,
  hwModuleTypeGet,
  hwModuleTypeGetInputName,
  hwModuleTypeGetInputType,
  hwModuleTypeGetNumInputs,
  hwModuleTypeGetNumOutputs,
  hwModuleTypeGetOutputName,
  hwModuleTypeGetOutputType,
  hwModuleTypeGetPort,
  hwParamIntTypeGet,
  hwParamIntTypeGetWidthAttr,
  hwStructTypeGet,
  hwStructTypeGetField,
  hwStructTypeGetNumFields,
  hwTypeAliasTypeGet,
  hwTypeAliasTypeGetCanonicalType,
  hwTypeAliasTypeGetInnerType,
  hwTypeAliasTypeGetName,
  hwTypeAliasTypeGetScope,
  hwTypeIsAArrayType,
  hwTypeIsAInOut,
  hwTypeIsAIntType,
  hwTypeIsAModuleType,
  hwTypeIsAStructType,
  hwTypeIsATypeAliasType,
  hwTypeIsAValueType
}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type, given}

import java.lang.foreign.{Arena, MemorySegment}

given TypeApi with
  def arrayTypeGet(
    element:     Type,
    size:        Int
  )(
    using arena: Arena
  ): Type = Type(hwArrayTypeGet(arena, element.segment, size))
  extension (tpe: Type)
    def arrayTypeGetElementType(
      using arena: Arena
    ) = Type(hwArrayTypeGetElementType(arena, tpe.segment))
    def arrayTypeGetSize(): Int = hwArrayTypeGetSize(tpe.segment).toInt
    def getBitWidth():      Int = hwGetBitWidth(tpe.segment).toInt
    def inOutTypeGet(
    )(
      using arena: Arena
    ) = Type(hwInOutTypeGet(arena, tpe.segment))
    def inOutTypeGetElementType(
    )(
      using arena: Arena
    ) = Type(hwInOutTypeGetElementType(arena, tpe.segment))
  def moduleTypeGet(
    numPorts:    Int,
    ports:       Seq[HWModulePort]
  )(
    using arena: Arena,
    context:     Context
  ): Type = Type(hwModuleTypeGet(arena, context.segment, numPorts, ports.toMlirArray))
  extension (tpe: Type)
    def moduleTypeGetInputName(
      index:       Int
    )(
      using arena: Arena
    ): String = StringRef(hwModuleTypeGetInputName(arena, tpe.segment, index)).toScalaString
    def moduleTypeGetInputType(
      index:       Int
    )(
      using arena: Arena
    ): Type = Type(hwModuleTypeGetInputType(arena, tpe.segment, index))
    def moduleTypeGetNumInputs(): Int = hwModuleTypeGetNumInputs(tpe.segment).toInt
    def moduleTypeGetNumOutputs(
    )(
      using arena: Arena
    ): Int = hwModuleTypeGetNumOutputs(tpe.segment).toInt
    def moduleTypeGetOutputName(
      index:       Int
    )(
      using arena: Arena
    ): String = StringRef(hwModuleTypeGetOutputName(arena, tpe.segment, index)).toScalaString
    def moduleTypeGetOutputType(
      index:       Int
    )(
      using arena: Arena
    ): Type = Type(hwModuleTypeGetOutputType(arena, tpe.segment, index))
    // Decodes the HWModuleType port at `index` in declaration order (the
    // unified order across inputs + outputs). The returned HWModulePort
    // exposes `portName` (MlirAttribute, typically a StringAttr), `portType`
    // (MlirType segment), and `portDirection` (0=input, 1=output, 2=inout).
    def moduleTypeGetPort(
      index:       Int
    )(
      using arena: Arena
    ): HWModulePort =
      val portSegment = org.llvm.circt.HWModulePort.allocate(arena)
      hwModuleTypeGetPort(tpe.segment, index, portSegment)
      HWModulePort(portSegment)
  def paramIntTypeGet(
    attribute:   Attribute
  )(
    using arena: Arena
  ): Type = Type(hwParamIntTypeGet(arena, attribute.segment))
  extension (tpe: Type)
    def paramIntTypeGetWidthAttr(
    )(
      using arena: Arena
    ): Attribute = Attribute(hwParamIntTypeGetWidthAttr(arena, tpe.segment))
  def structTypeGet(
    numElements: Int,
    elements:    Seq[HWStructFieldInfo]
  )(
    using arena: Arena,
    context:     Context
  ): Type = Type(hwStructTypeGet(arena, context.segment, numElements, elements.toMlirArray))
  extension (tpe: Type)
    def structTypeGetField(
      name:        String
    )(
      using arena: Arena
    ): Type = Type(hwStructTypeGetField(arena, tpe.segment, name.toStringRef.segment))
    def structTypeGetNumFields(
    )(
      using arena: Arena
    ): Int = hwStructTypeGetNumFields(tpe.segment).toInt
  def typeAliasTypeGet(
    scope:       String,
    name:        String,
    innerType:   Type
  )(
    using arena: Arena
  ): Type = Type(hwTypeAliasTypeGet(arena, scope.toStringRef.segment, name.toStringRef.segment, innerType.segment))
  extension (tpe: Type)
    def typeAliasTypeGetCanonicalType(
    )(
      using arena: Arena
    ): Type = Type(hwTypeAliasTypeGetCanonicalType(arena, tpe.segment))
    def typeAliasTypeGetInnerType(
    )(
      using arena: Arena
    ): Type = Type(hwTypeAliasTypeGetInnerType(arena, tpe.segment))
    def typeAliasTypeGetName(
    )(
      using arena: Arena
    ): String = StringRef(hwTypeAliasTypeGetName(arena, tpe.segment)).toScalaString
    def typeAliasTypeGetScope(
    )(
      using arena: Arena
    ): String = StringRef(hwTypeAliasTypeGetScope(arena, tpe.segment)).toScalaString
  extension (tpe: Type)
    def isArrayType():     Boolean = hwTypeIsAArrayType(tpe.segment)
    def isInOut():         Boolean = hwTypeIsAInOut(tpe.segment)
    def isIntType():       Boolean = hwTypeIsAIntType(tpe.segment)
    def isModuleType():    Boolean = hwTypeIsAModuleType(tpe.segment)
    def isStructType():    Boolean = hwTypeIsAStructType(tpe.segment)
    def isTypeAliasType(): Boolean = hwTypeIsATypeAliasType(tpe.segment)
    def isValueType():     Boolean = hwTypeIsAValueType(tpe.segment)
end given
