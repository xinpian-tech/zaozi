// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/LLHD.h
package org.llvm.circt.scalalib.capi.dialect.llhd

import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type, given}

import scala.scalanative.unsafe.Zone

/** LLHD Dialect Api
  *
  * {{{
  * mlirGetDialectHandle__llhd__
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ): Unit
end DialectApi

/** LLHD Attribute API
  *
  * {{{
  * llhdAttrIsATimeAttr
  * llhdTimeAttrGet
  * llhdTimeAttrGetDelta
  * llhdTimeAttrGetEpsilon
  * llhdTimeAttrGetSeconds
  * llhdTimeAttrGetTimeUnit
  * }}}
  */
trait AttributeApi:
  extension (attribute: Attribute) def isTimeAttr(): Boolean
  def TimeAttrGet(
    timeUnit:   String,
    seconds:    BigInt,
    delta:      BigInt,
    epsilon:    BigInt
  )(
    using zone: Zone,
    context:    Context
  ):                                                 Attribute
  extension (attribute: Attribute)
    def TimeAttrGetDelta():   BigInt
    def TimeAttrGetEpsilon(): BigInt
    def TimeAttrGetSeconds(): BigInt
    def TimeAttrGetTimeUnit(
    )(
      using zone: Zone
    ):                        String
end AttributeApi

/** LLHD Type API
  *
  * {{{
  * llhdTypeIsATimeType
  * }}}
  */
trait TypeApi:
  extension (tpe: Type) inline def isTimeType: Boolean
end TypeApi
