// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.seq

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.{Context, Type, given}

given TypeApi with
  def clockTypeGet(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.seqClockTypeGet(context.native)(out)))
  def immutableTypeGet(
    tpe:        Type
  )(
    using zone: Zone
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.seqImmutableTypeGet(tpe.native)(out)))
  def immutableTypeGetInnerType(
    tpe:        Type
  )(
    using zone: Zone
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.seqImmutableTypeGetInnerType(tpe.native)(out)))
  extension (tpe: Type)
    def isClock:     Boolean = circtApi.seqTypeIsAClock(tpe.native)
    def isImmutable: Boolean = circtApi.seqTypeIsAImmutable(tpe.native)
end given
