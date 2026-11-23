// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.moore

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, Type, given}

given TypeApi with
  inline def arrayTypeGet(
    size:        Int,
    elementType: Type
  )(
    using zone:  Zone
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out => circtApi.mooreArrayTypeGet(size.toUInt, elementType.native)(out))
  )
  inline def assocArrayTypeGet(
    elementType: Type,
    indexType:   Type
  )(
    using zone:  Zone
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out =>
      circtApi.mooreAssocArrayTypeGet(elementType.native, indexType.native)(out)
    )
  )
  inline def chandleTypeGet(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.mooreChandleTypeGet(context.native)(out)))
  inline def eventTypeGet(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.mooreEventTypeGet(context.native)(out)))
  inline def intTypeGetInt(
    width:      Int
  )(
    using zone: Zone,
    context:    Context
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out => circtApi.mooreIntTypeGetInt(context.native, width.toUInt)(out))
  )
  inline def intTypeGetLogic(
    width:      Int
  )(
    using zone: Zone,
    context:    Context
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out => circtApi.mooreIntTypeGetLogic(context.native, width.toUInt)(out))
  )
  extension (tpe: Type)
    inline def isFourValuedType(): Boolean = circtApi.mooreIsFourValuedType(tpe.native)
    inline def isTwoValuedType():  Boolean = circtApi.mooreIsTwoValuedType(tpe.native)
  inline def openArrayTypeGet(
    elementType: Type
  )(
    using zone:  Zone
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.mooreOpenArrayTypeGet(elementType.native)(out)))
  inline def openUnpackedArrayTypeGet(
    elementType: Type
  )(
    using zone:  Zone
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out => circtApi.mooreOpenUnpackedArrayTypeGet(elementType.native)(out))
  )
  inline def queueTypeGet(
    elementType: Type,
    bound:       Int
  )(
    using zone:  Zone
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out => circtApi.mooreQueueTypeGet(elementType.native, bound.toUInt)(out))
  )
  inline def realTypeGet(
    width:      Int
  )(
    using zone: Zone,
    context:    Context
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out => circtApi.mooreRealTypeGet(context.native, width.toUInt)(out))
  )
  inline def stringTypeGet(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.mooreStringTypeGet(context.native)(out)))
  inline def unpackedArrayTypeGet(
    size:        Int,
    elementType: Type
  )(
    using zone:  Zone
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out => circtApi.mooreUnpackedArrayTypeGet(size.toUInt, elementType.native)(out))
  )
  inline def voidTypeGet(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.mooreVoidTypeGet(context.native)(out)))
end given
