// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package org.llvm.mlir.scalalib.capi.dialect.smt

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, Type, given}

given TypeApi with
  inline def getArray(
    domainType: Type,
    rangeType:  Type
  )(
    using zone: Zone,
    context:    Context
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out =>
      mlirApi.mlirSMTTypeGetArray(context.native, domainType.native, rangeType.native)(out)
    )
  )
  inline def getBitVector(
    width:      Int
  )(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => mlirApi.mlirSMTTypeGetBitVector(context.native, width)(out)))
  inline def getBool(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => mlirApi.mlirSMTTypeGetBool(context.native)(out)))
  inline def getInt(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => mlirApi.mlirSMTTypeGetInt(context.native)(out)))
  inline def getSMTFunc(
    domainTypes: Seq[Type],
    rangeType:   Type
  )(
    using zone:  Zone,
    context:     Context
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out =>
      mlirApi.mlirSMTTypeGetSMTFunc(
        context.native,
        domainTypes.length.toUSize,
        domainTypes.toMlirArray,
        rangeType.native
      )(out)
    )
  )
  inline def getSort(
    identifier: String,
    sortParams: Seq[Type]
  )(
    using zone: Zone,
    context:    Context
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out =>
      mlirApi.mlirSMTTypeGetSort(
        context.native,
        identifier.identifierGet.native,
        sortParams.length.toUSize,
        sortParams.toMlirArray
      )(out)
    )
  )

  extension (tpe: Type)
    inline def isAnyNonFuncSMTValueType: Boolean = mlirApi.mlirSMTTypeIsAnyNonFuncSMTValueType(tpe.native)
    inline def isAnySMTValueType:        Boolean = mlirApi.mlirSMTTypeIsAnySMTValueType(tpe.native)
    inline def isArray:                  Boolean = mlirApi.mlirSMTTypeIsAArray(tpe.native)
    inline def isBitVector:              Boolean = mlirApi.mlirSMTTypeIsABitVector(tpe.native)
    inline def isBool:                   Boolean = mlirApi.mlirSMTTypeIsABool(tpe.native)
    inline def isInt:                    Boolean = mlirApi.mlirSMTTypeIsAInt(tpe.native)
    inline def isSMTFunc:                Boolean = mlirApi.mlirSMTTypeIsASMTFunc(tpe.native)
    inline def isSort:                   Boolean = mlirApi.mlirSMTTypeIsASort(tpe.native)

end given
