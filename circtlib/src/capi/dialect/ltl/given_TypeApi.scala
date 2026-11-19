// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.ltl

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.{Context, Type, given}

given TypeApi with
  inline def sequenceTypeGet(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.ltlSequenceTypeGet(context.native)(out)))
  inline def propertyTypeGet(
    using zone: Zone,
    context:    Context
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.ltlPropertyTypeGet(context.native)(out)))
  extension (tpe: Type)
    inline def isSequence: Boolean = circtApi.ltlTypeIsASequence(tpe.native)
    inline def isProperty: Boolean = circtApi.ltlTypeIsAProperty(tpe.native)
end given
