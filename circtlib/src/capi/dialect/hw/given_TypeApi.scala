// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.hw

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.structs as CirctStructs
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type, given}

given TypeApi with
  def arrayTypeGet(
    element:    Type,
    size:       Int
  )(
    using zone: Zone
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.hwArrayTypeGet(element.native, size.toUSize)(out)))
  extension (tpe: Type)
    def arrayTypeGetElementType(
      using zone: Zone
    ) = Type(nativeResult[MlirStructs.MlirType](out => circtApi.hwArrayTypeGetElementType(tpe.native)(out)))
    def arrayTypeGetSize(): Int = circtApi.hwArrayTypeGetSize(tpe.native).toLong.toInt
    def getBitWidth():      Int = circtApi.hwGetBitWidth(tpe.native).toLong.toInt
    def inOutTypeGet(
    )(
      using zone: Zone
    ) = Type(nativeResult[MlirStructs.MlirType](out => circtApi.hwInOutTypeGet(tpe.native)(out)))
    def inOutTypeGetElementType(
    )(
      using zone: Zone
    ) = Type(nativeResult[MlirStructs.MlirType](out => circtApi.hwInOutTypeGetElementType(tpe.native)(out)))
  def moduleTypeGet(
    numPorts:   Int,
    ports:      Seq[HWModulePort]
  )(
    using zone: Zone,
    context:    Context
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out =>
      circtApi.hwModuleTypeGet(context.native, numPorts.toSize, ports.toMlirArray)(out)
    )
  )
  extension (tpe: Type)
    def moduleTypeGetInputName(
      index:      Int
    )(
      using zone: Zone
    ): String = StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => circtApi.hwModuleTypeGetInputName(tpe.native, index.toSize)(out))
    ).toScalaString
    def moduleTypeGetInputType(
      index:      Int
    )(
      using zone: Zone
    ): Type = Type(
      nativeResult[MlirStructs.MlirType](out => circtApi.hwModuleTypeGetInputType(tpe.native, index.toSize)(out))
    )
    def moduleTypeGetNumInputs(): Int = circtApi.hwModuleTypeGetNumInputs(tpe.native).toLong.toInt
    def moduleTypeGetNumOutputs(
    )(
      using zone: Zone
    ): Int = circtApi.hwModuleTypeGetNumOutputs(tpe.native).toLong.toInt
    def moduleTypeGetOutputName(
      index:      Int
    )(
      using zone: Zone
    ): String = StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => circtApi.hwModuleTypeGetOutputName(tpe.native, index.toSize)(out))
    ).toScalaString
    def moduleTypeGetOutputType(
      index:      Int
    )(
      using zone: Zone
    ): Type = Type(
      nativeResult[MlirStructs.MlirType](out => circtApi.hwModuleTypeGetOutputType(tpe.native, index.toSize)(out))
    )
    // Decodes the HWModuleType port at `index` in declaration order (the
    // unified order across inputs + outputs). The returned HWModulePort
    // exposes `portName` (MlirAttribute, typically a StringAttr), `portType`
    // (MlirType segment), and `portDirection` (0=input, 1=output, 2=inout).
    def moduleTypeGetPort(
      index:      Int
    )(
      using zone: Zone
    ): HWModulePort =
      val portSegment = alloc[CirctStructs.HWModulePort]()
      circtApi.hwModuleTypeGetPort(tpe.native, index.toSize, portSegment)
      HWModulePort(portSegment)
  def paramIntTypeGet(
    attribute:  Attribute
  )(
    using zone: Zone
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.hwParamIntTypeGet(attribute.native)(out)))
  extension (tpe: Type)
    def paramIntTypeGetWidthAttr(
    )(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => circtApi.hwParamIntTypeGetWidthAttr(tpe.native)(out))
    )
  def structTypeGet(
    numElements: Int,
    elements:    Seq[HWStructFieldInfo]
  )(
    using zone:  Zone,
    context:     Context
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out =>
      circtApi.hwStructTypeGet(context.native, numElements.toSize, elements.toMlirArray)(out)
    )
  )
  extension (tpe: Type)
    def structTypeGetField(
      name:       String
    )(
      using zone: Zone
    ): Type = Type(
      nativeResult[MlirStructs.MlirType](out => circtApi.hwStructTypeGetField(tpe.native, name.toStringRef.native)(out))
    )
    def structTypeGetNumFields(
    )(
      using zone: Zone
    ): Int = circtApi.hwStructTypeGetNumFields(tpe.native).toLong.toInt
  def typeAliasTypeGet(
    scope:      String,
    name:       String,
    innerType:  Type
  )(
    using zone: Zone
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out =>
      circtApi.hwTypeAliasTypeGet(scope.toStringRef.native, name.toStringRef.native, innerType.native)(out)
    )
  )
  extension (tpe: Type)
    def typeAliasTypeGetCanonicalType(
    )(
      using zone: Zone
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.hwTypeAliasTypeGetCanonicalType(tpe.native)(out)))
    def typeAliasTypeGetInnerType(
    )(
      using zone: Zone
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.hwTypeAliasTypeGetInnerType(tpe.native)(out)))
    def typeAliasTypeGetName(
    )(
      using zone: Zone
    ): String = StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => circtApi.hwTypeAliasTypeGetName(tpe.native)(out))
    ).toScalaString
    def typeAliasTypeGetScope(
    )(
      using zone: Zone
    ): String = StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => circtApi.hwTypeAliasTypeGetScope(tpe.native)(out))
    ).toScalaString
  extension (tpe: Type)
    def isArrayType():     Boolean = circtApi.hwTypeIsAArrayType(tpe.native)
    def isInOut():         Boolean = circtApi.hwTypeIsAInOut(tpe.native)
    def isIntType():       Boolean = circtApi.hwTypeIsAIntType(tpe.native)
    def isModuleType():    Boolean = circtApi.hwTypeIsAModuleType(tpe.native)
    def isStructType():    Boolean = circtApi.hwTypeIsAStructType(tpe.native)
    def isTypeAliasType(): Boolean = circtApi.hwTypeIsATypeAliasType(tpe.native)
    def isValueType():     Boolean = circtApi.hwTypeIsAValueType(tpe.native)
end given
