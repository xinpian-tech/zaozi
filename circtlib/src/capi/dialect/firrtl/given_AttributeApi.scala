// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.circt.*
import org.llvm.circt.CAPI.{mlirGetDialectHandle__firrtl__ as _, *}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type, TypeApi, given}

import java.lang.foreign.{Arena, MemorySegment}

given AttributeApi with
  inline def paramDeclAttrGet(
    name:        String,
    tpe:         Type,
    value:       Attribute
  )(
    using arena: Arena,
    context:     Context
  ): Attribute =
    Attribute(firrtlAttrGetParamDecl(arena, context.segment, name.identifierGet.segment, tpe.segment, value.segment))

  extension (firrtlConvention:      FirrtlConvention)
    inline def toAttribute(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(firrtlAttrGetConvention(arena, context.segment, firrtlConvention.toNative))
  extension (ref:                   FirrtlEventControl)
    inline def attrGetEventControl(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(
      firrtlAttrGetEventControl(arena, context.segment, ref.toNative)
    )
  extension (bigInt:                BigInt)
    inline def attrGetIntegerFromString(
      tpe:         Type,
      width:       Option[Int] = None
    )(
      using arena: Arena
    ): Attribute =
      val widthInt = width.getOrElse(tpe.integerTypeGetWidth.toInt)
      require(
        widthInt >= bigInt.bitLength,
        s"Provided width ${widthInt} is smaller than the bit length ${bigInt.bitLength} of the BigInt ${bigInt}"
      )
      Attribute(
        firrtlAttrGetIntegerFromString(
          arena,
          tpe.segment,
          widthInt,
          bigInt.toString(10).toStringRef.segment,
          10
        )
      )
  extension (firrtlLayerConvention: FirrtlLayerConvention)
    inline def toAttribute(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(firrtlAttrGetLayerConvention(arena, context.segment, firrtlLayerConvention.toNative))
  extension (memDir:                FirrtlMemDir)
    inline def toAttribute(
      using arena: Arena,
      context:     Context
    ): MemorySegment = firrtlAttrGetMemDir(arena, context.segment, memDir.toNative)
  inline def getMemInit(
    filename:    String,
    isBinary:    Boolean,
    isInline:    Boolean
  )(
    using arena: Arena,
    context:     Context
  ): Attribute = Attribute(
    firrtlAttrGetMemInit(
      arena,
      context.segment,
      filename.identifierGet.segment,
      isBinary,
      isInline
    )
  )
  extension (ref:                   FirrtlNameKind)
    inline def attrGetNameKind(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(firrtlAttrGetNameKind(arena, context.segment, ref.toNative))
  extension (value:                 String)
    inline def getParamDeclAttribute(
      name:        String
    )(
      using arena: Arena,
      context:     Context
    ): Attribute =
      val attr = value.stringAttrGet
      Attribute(
        firrtlAttrGetParamDecl(
          arena,
          context.segment,
          name.identifierGet.segment,
          attr.getType.segment,
          attr.segment
        )
      )
  extension (value:                 BigInt)
    inline def getParamDeclAttribute(
      name:        String
    )(
      using arena: Arena,
      context:     Context
    ): Attribute =
      // XXX: should we limit to unsigned?
      val tpe = value.bitLength.integerTypeUnsignedGet
      Attribute(
        firrtlAttrGetParamDecl(
          arena,
          context.segment,
          name.identifierGet.segment,
          tpe.segment,
          value.attrGetIntegerFromString(tpe).segment
        )
      )
  extension (value:                 Double)
    inline def getParamDeclAttribute(
      name:        String
    )(
      using arena: Arena,
      context:     Context
    ): Attribute =
      val tpe = summon[TypeApi].f64TypeGet
      Attribute(
        firrtlAttrGetParamDecl(
          arena,
          context.segment,
          name.identifierGet.segment,
          tpe.segment,
          value.floatAttrDoubleGet(tpe).segment
        )
      )
  extension (value:                 Boolean)
    inline def getParamDeclAttribute(
      name:        String
    )(
      using arena: Arena,
      context:     Context
    ): Attribute =
      val attr = value.boolAttrGet
      Attribute(
        firrtlAttrGetParamDecl(
          arena,
          context.segment,
          name.identifierGet.segment,
          attr.getType.segment,
          attr.segment
        )
      )
  extension (portDirections:        Seq[FirrtlDirection])
    inline def attrGetPortDirs(
      using arena: Arena,
      context:     Context
    ): Attribute =
      Attribute(firrtlAttrGetPortDirs(arena, context.segment, portDirections.size, portDirections.toMlirArray))
  extension (ruw:                   FirrtlRUW)
    inline def toAttribute(
      using arena: Arena,
      context:     Context
    ): MemorySegment = firrtlAttrGetRUW(arena, context.segment, ruw.toNative)
end given
