// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/Moore.h
package org.llvm.circt.scalalib.capi.dialect.moore

import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import scala.scalanative.unsafe.Zone

/** Arc Dialect Api
  * {{{
  * mlirGetDialectHandle__moore__
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ): Unit
end DialectApi

/** Moore Type API
  * {{{
  * mooreArrayTypeGet
  * mooreAssocArrayTypeGet
  * mooreChandleTypeGet
  * mooreEventTypeGet
  * mooreIntTypeGetInt
  * mooreIntTypeGetLogic
  * mooreIsFourValuedType
  * mooreIsTwoValuedType
  * mooreOpenArrayTypeGet
  * mooreOpenUnpackedArrayTypeGet
  * mooreQueueTypeGet
  * mooreRealTypeGet
  * mooreStringTypeGet
  * mooreUnpackedArrayTypeGet
  * mooreVoidTypeGet
  * }}}
  */
trait TypeApi:
  inline def arrayTypeGet(
    size:        Int,
    elementType: Type
  )(
    using zone:  Zone
  ): Type
  inline def assocArrayTypeGet(
    elementType: Type,
    indexType:   Type
  )(
    using zone:  Zone
  ): Type
  inline def chandleTypeGet(
    using zone: Zone,
    context:    Context
  ): Type
  inline def eventTypeGet(
    using zone: Zone,
    context:    Context
  ): Type
  inline def intTypeGetInt(
    width:      Int
  )(
    using zone: Zone,
    context:    Context
  ): Type
  inline def intTypeGetLogic(
    width:      Int
  )(
    using zone: Zone,
    context:    Context
  ): Type
  extension (tpe: Type)
    inline def isFourValuedType(): Boolean
    inline def isTwoValuedType():  Boolean
  inline def openArrayTypeGet(
    elementType: Type
  )(
    using zone:  Zone
  ): Type
  inline def openUnpackedArrayTypeGet(
    elementType: Type
  )(
    using zone:  Zone
  ): Type
  inline def queueTypeGet(
    elementType: Type,
    bound:       Int
  )(
    using zone:  Zone
  ): Type
  inline def realTypeGet(
    width:      Int
  )(
    using zone: Zone,
    context:    Context
  ): Type
  inline def stringTypeGet(
    using zone: Zone,
    context:    Context
  ): Type
  inline def unpackedArrayTypeGet(
    size:        Int,
    elementType: Type
  )(
    using zone:  Zone
  ): Type
  inline def voidTypeGet(
    using zone: Zone,
    context:    Context
  ): Type
end TypeApi
