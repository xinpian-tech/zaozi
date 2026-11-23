// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.enumerations.*
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type, TypeApi, given}

given AttributeApi with
  extension (firrtlConvention:      FirrtlConvention)
    inline def toAttribute(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        circtApi.firrtlAttrGetConvention(context.native, FIRRTLConvention.define(firrtlConvention.toNative))(out)
      )
    )
  extension (ref:                   FirrtlEventControl)
    inline def attrGetEventControl(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        circtApi.firrtlAttrGetEventControl(context.native, FIRRTLEventControl.define(ref.toNative))(out)
      )
    )
  extension (bigInt:                BigInt)
    inline def attrGetIntegerFromString(
      tpe:        Type,
      width:      Option[Int] = None
    )(
      using zone: Zone
    ): Attribute =
      val widthInt = width.getOrElse(tpe.integerTypeGetWidth.toInt)
      require(
        widthInt >= bigInt.bitLength,
        s"Provided width ${widthInt} is smaller than the bit length ${bigInt.bitLength} of the BigInt ${bigInt}"
      )
      Attribute(
        nativeResult[MlirStructs.MlirAttribute](out =>
          circtApi.firrtlAttrGetIntegerFromString(
            tpe.native,
            widthInt.toUInt,
            bigInt.toString(10).toStringRef.native,
            10.toUByte
          )(out)
        )
      )
  extension (firrtlLayerConvention: FirrtlLayerConvention)
    inline def toAttribute(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        circtApi.firrtlAttrGetLayerConvention(
          context.native,
          FIRRTLLayerConvention.define(firrtlLayerConvention.toNative)
        )(out)
      )
    )
  extension (memDir:                FirrtlMemDir)
    inline def toAttribute(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        circtApi.firrtlAttrGetMemDir(context.native, FIRRTLMemDir.define(memDir.toNative))(out)
      )
    )
  inline def getMemInit(
    filename:   String,
    isBinary:   Boolean,
    isInline:   Boolean
  )(
    using zone: Zone,
    context:    Context
  ): Attribute = Attribute(
    nativeResult[MlirStructs.MlirAttribute](out =>
      circtApi.firrtlAttrGetMemInit(context.native, filename.identifierGet.native, isBinary, isInline)(out)
    )
  )
  extension (ref:                   FirrtlNameKind)
    inline def attrGetNameKind(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        circtApi.firrtlAttrGetNameKind(context.native, FIRRTLNameKind.define(ref.toNative))(out)
      )
    )
  extension (value:                 String)
    inline def getParamDeclAttribute(
      name:       String
    )(
      using zone: Zone,
      context:    Context
    ): Attribute =
      val attr = value.stringAttrGet
      Attribute(
        nativeResult[MlirStructs.MlirAttribute](out =>
          circtApi.firrtlAttrGetParamDecl(context.native, name.identifierGet.native, attr.getType.native, attr.native)(
            out
          )
        )
      )
  extension (value:                 BigInt)
    inline def getParamDeclAttribute(
      name:       String
    )(
      using zone: Zone,
      context:    Context
    ): Attribute =
      // XXX: should we limit to unsigned?
      val tpe = value.bitLength.integerTypeUnsignedGet
      Attribute(
        nativeResult[MlirStructs.MlirAttribute](out =>
          circtApi.firrtlAttrGetParamDecl(
            context.native,
            name.identifierGet.native,
            tpe.native,
            value.attrGetIntegerFromString(tpe).native
          )(out)
        )
      )
  extension (value:                 Double)
    inline def getParamDeclAttribute(
      name:       String
    )(
      using zone: Zone,
      context:    Context
    ): Attribute =
      val tpe = summon[TypeApi].f64TypeGet
      Attribute(
        nativeResult[MlirStructs.MlirAttribute](out =>
          circtApi.firrtlAttrGetParamDecl(
            context.native,
            name.identifierGet.native,
            tpe.native,
            value.floatAttrDoubleGet(tpe).native
          )(out)
        )
      )
  extension (value:                 Boolean)
    inline def getParamDeclAttribute(
      name:       String
    )(
      using zone: Zone,
      context:    Context
    ): Attribute =
      val attr = value.boolAttrGet
      Attribute(
        nativeResult[MlirStructs.MlirAttribute](out =>
          circtApi.firrtlAttrGetParamDecl(context.native, name.identifierGet.native, attr.getType.native, attr.native)(
            out
          )
        )
      )
  extension (portDirections:        Seq[FirrtlDirection])
    inline def attrGetPortDirs(
      using zone: Zone,
      context:    Context
    ): Attribute =
      Attribute(
        nativeResult[MlirStructs.MlirAttribute](out =>
          circtApi.firrtlAttrGetPortDirs(
            context.native,
            portDirections.size.toUSize,
            nativeArray(portDirections.map(d => FIRRTLDirection.define(d.toNative)))
          )(out)
        )
      )
  extension (ruw:                   FirrtlRUW)
    inline def toAttribute(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        circtApi.firrtlAttrGetRUW(context.native, FIRRTLRUW.define(ruw.toNative))(out)
      )
    )
end given
