// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/Seq.h
package org.llvm.circt.scalalib.capi.dialect.seq

import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import scala.scalanative.unsafe.Zone

/** Seq Dialect Api
  *
  * {{{
  * mlirGetDialectHandle__seq__
  * registerSeqPasses
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ):                  Unit
  def registerPasses: Unit
end DialectApi

/** Seq Type API
  *
  * {{{
  * seqClockTypeGet
  * seqImmutableTypeGet
  * seqImmutableTypeGetInnerType
  * seqTypeIsAClock
  * seqTypeIsAImmutable
  * }}}
  */
trait TypeApi:
  def clockTypeGet(
    using zone: Zone,
    context:    Context
  ): Type
  def immutableTypeGet(
    tpe:        Type
  )(
    using zone: Zone
  ): Type
  def immutableTypeGetInnerType(
    tpe:        Type
  )(
    using zone: Zone
  ): Type
  extension (tpe: Type)
    def isClock:     Boolean
    def isImmutable: Boolean
end TypeApi
