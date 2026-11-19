// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package org.llvm.circt.scalalib.capi.dialect.om

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.ir.{Attribute, Identifier, given}

given AttributeApi with
  extension (attr: Attribute)
    inline def isIntegerAttr:          Boolean = circtApi.omAttrIsAIntegerAttr(attr.native)
    inline def isListAttr:             Boolean = circtApi.omAttrIsAListAttr(attr.native)
    inline def isReferenceAttr:        Boolean = circtApi.omAttrIsAReferenceAttr(attr.native)
    inline def integerAttrGet(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => circtApi.omIntegerAttrGet(attr.native)(out))
    )
    inline def integerAttrGetInt(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => circtApi.omIntegerAttrGetInt(attr.native)(out))
    )
    inline def integerAttrToString(
      using zone: Zone
    ): String =
      nativeResult[MlirStructs.MlirStringRef](out => circtApi.omIntegerAttrToString(attr.native)(out)).toString
    inline def listAttrGetElement(
      pos:        Int
    )(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => circtApi.omListAttrGetElement(attr.native, pos.toSize)(out))
    )
    inline def listAttrGetNumElements: Int     = circtApi.omListAttrGetNumElements(attr.native).toLong.toInt
    inline def referenceAttrGetInnerRef(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => circtApi.omReferenceAttrGetInnerRef(attr.native)(out))
    )
end given
