// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.llhd

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, given}

given AttributeApi with
  extension (attribute: Attribute) def isTimeAttr(): Boolean = circtApi.llhdAttrIsATimeAttr(attribute.native)
  def TimeAttrGet(
    timeUnit:   String,
    seconds:    BigInt,
    delta:      BigInt,
    epsilon:    BigInt
  )(
    using zone: Zone,
    context:    Context
  ): Attribute = Attribute(
    nativeResult[MlirStructs.MlirAttribute](out =>
      circtApi.llhdTimeAttrGet(
        context.native,
        timeUnit.toStringRef.native,
        seconds.toLong.toULong,
        delta.toLong.toULong,
        epsilon.toLong.toULong
      )(out)
    )
  )
  extension (attribute: Attribute)
    def TimeAttrGetDelta():   BigInt = circtApi.llhdTimeAttrGetDelta(attribute.native).toLong
    def TimeAttrGetEpsilon(): BigInt = circtApi.llhdTimeAttrGetEpsilon(attribute.native).toLong
    def TimeAttrGetSeconds(): BigInt = circtApi.llhdTimeAttrGetSeconds(attribute.native).toLong
    def TimeAttrGetTimeUnit(
    )(
      using zone: Zone
    ): String = StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => circtApi.llhdTimeAttrGetTimeUnit(attribute.native)(out))
    ).toScalaString
end given
