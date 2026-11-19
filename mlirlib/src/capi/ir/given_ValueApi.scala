// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given ValueApi with
  extension (value: Value)
    inline def argumentGetOwner(
      using Zone
    ): Block = Block(nativeResult[MlirStructs.MlirBlock](out => mlirApi.mlirBlockArgumentGetOwner(value.native)(out)))
    inline def getType(
      using zone: Zone
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => mlirApi.mlirValueGetType(value.native)(out)))
    inline def getFirstUse(
      using zone: Zone
    ): OpOperand = OpOperand(
      nativeResult[MlirStructs.MlirOpOperand](out => mlirApi.mlirValueGetFirstUse(value.native)(out))
    )
    inline def setType(tpe:            Type):  Unit    = mlirApi.mlirValueSetType(value.native, tpe.native)
    inline def dump():                         Unit    = mlirApi.mlirValueDump(value.native)
    inline def print(
      callback:   String => Unit
    )(
      using zone: Zone
    ): Unit = callback.stringToStringCallback.use { (fn, data) => mlirApi.mlirValuePrint(value.native, fn, data) }
    inline def printAsOperand(
      state:      AsmState,
      callback:   String => Unit
    )(
      using zone: Zone
    ): Unit =
      callback.stringToStringCallback.use { (fn, data) =>
        mlirApi.mlirValuePrintAsOperand(value.native, state.native, fn, data)
      }
    inline def replaceAllUsesOfWith(w: Value): Unit    = mlirApi.mlirValueReplaceAllUsesOfWith(value.native, w.native)
    inline def isOpResult:                     Boolean = mlirApi.mlirValueIsAOpResult(value.native)
    inline def isBlockArgument:                Boolean = mlirApi.mlirValueIsABlockArgument(value.native)
    inline def opResultGetOwner(
      using zone: Zone
    ): Operation = Operation(
      nativeResult[MlirStructs.MlirOperation](out => mlirApi.mlirOpResultGetOwner(value.native)(out))
    )
    inline def opResultGetResultNumber:        Long    = mlirApi.mlirOpResultGetResultNumber(value.native).toLong
    inline def blockArgumentGetOwner(
      using zone: Zone
    ): Block = Block(nativeResult[MlirStructs.MlirBlock](out => mlirApi.mlirBlockArgumentGetOwner(value.native)(out)))
    inline def blockArgumentGetArgNumber:      Long    = mlirApi.mlirBlockArgumentGetArgNumber(value.native).toLong

    inline def sizeOf: Int = sizeof[MlirStructs.MlirValue].toInt
end given
