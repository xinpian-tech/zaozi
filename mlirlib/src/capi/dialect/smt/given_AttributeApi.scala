// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package org.llvm.mlir.scalalib.capi.dialect.smt

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, given}

given AttributeApi with
  extension (str:   String)
    inline def getBVCmpPredicateAttribute(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirSMTAttrGetBVCmpPredicate(context.native, str.toStringRef.native)(out)
      )
    )
    inline def getIntPredicateAttribute(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirSMTAttrGetIntPredicate(context.native, str.toStringRef.native)(out)
      )
    )
  extension (value: Int)
    inline def getBitVectorAttribute(
      width:      Int
    )(
      using zone: Zone,
      context:    Context
    ): Attribute =
      require(
        width >= (if value == 0 then 1 else 32 - Integer.numberOfLeadingZeros(value)),
        s"Width $width is insufficient to represent unsigned value $value"
      )
      Attribute(
        nativeResult[MlirStructs.MlirAttribute](out =>
          mlirApi.mlirSMTAttrGetBitVector(context.native, value.toULong, width.toUInt)(out)
        )
      )
  extension (attr:  Attribute) inline def isSMTAttribute: Boolean = mlirApi.mlirSMTAttrIsASMTAttribute(attr.native)

  extension (str: String)
    inline def checkBVCmpPredicateAttribute(
      using zone: Zone,
      context:    Context
    ): Boolean = mlirApi.mlirSMTAttrCheckBVCmpPredicate(context.native, str.toStringRef.native)
    inline def checkIntPredicateAttribute(
      using zone: Zone,
      context:    Context
    ): Boolean = mlirApi.mlirSMTAttrCheckIntPredicate(context.native, str.toStringRef.native)
end given
