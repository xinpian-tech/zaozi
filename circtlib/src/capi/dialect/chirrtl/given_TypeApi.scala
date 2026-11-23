// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.chirrtl

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.ir.{Context, Type, given}

given TypeApi with
  inline def getCMemory(
    elementType: Type,
    numElements: Int
  )(
    using zone:  Zone,
    context:     Context
  ): Type = Type(
    nativeResult[MlirStructs.MlirType](out =>
      circtApi.chirrtlTypeGetCMemory(context.native, elementType.native, numElements.toULong)(out)
    )
  )
  inline def getCMemoryPort(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.chirrtlTypeGetCMemoryPort(context.native)(out)))
end given
