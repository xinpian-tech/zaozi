// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package org.llvm.circt.scalalib.capi.dialect.om

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Identifier, Type, given}

given TypeApi with
  inline def anyTypeGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => circtApi.omAnyTypeGetTypeID()(out)))
  extension (tpe: Type)
    inline def classTypeGetName(
      using zone: Zone
    ): Identifier = Identifier(
      nativeResult[MlirStructs.MlirIdentifier](out => circtApi.omClassTypeGetName(tpe.native)(out))
    )
  inline def classTypeGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => circtApi.omClassTypeGetTypeID()(out)))
  inline def frozenBasePathTypeGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => circtApi.omFrozenBasePathTypeGetTypeID()(out)))
  inline def frozenPathTypeGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => circtApi.omFrozenPathTypeGetTypeID()(out)))
  extension (tpe: Type)
    inline def listTypeGetElementType(
      using zone: Zone
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.omListTypeGetElementType(tpe.native)(out)))
  inline def listTypeGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => circtApi.omListTypeGetTypeID()(out)))
  extension (tpe: Type)
    inline def isAnyType:            Boolean = circtApi.omTypeIsAAnyType(tpe.native)
    inline def isClassType:          Boolean = circtApi.omTypeIsAClassType(tpe.native)
    inline def isFrozenBasePathType: Boolean = circtApi.omTypeIsAFrozenBasePathType(tpe.native)
    inline def isFrozenPathType:     Boolean = circtApi.omTypeIsAFrozenPathType(tpe.native)
    inline def isListType:           Boolean = circtApi.omTypeIsAListType(tpe.native)
    inline def isStringType:         Boolean = circtApi.omTypeIsAStringType(tpe.native)
end given
