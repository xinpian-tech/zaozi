// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/Chirrtl.h
package org.llvm.circt.scalalib.capi.dialect.chirrtl

import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import scala.scalanative.unsafe.Zone

/** Chirrtl Dialect API
  *
  * {{{
  * mlirGetDialectHandle__chirrtl__
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ): Unit
end DialectApi

/** Chirrtl Type API
  *
  * {{{
  * chirrtlTypeGetCMemory
  * chirrtlTypeGetCMemoryPort
  * }}}
  */
trait TypeApi:
  inline def getCMemory(
    elementType: Type,
    numElements: Int
  )(
    using zone:  Zone,
    context:     Context
  ): Type
  inline def getCMemoryPort(
    using zone: Zone,
    context:    Context
  ): Type
end TypeApi
