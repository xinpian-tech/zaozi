// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.*
import org.llvm.mlir.CAPI.{
  mlirBlockArgumentGetArgNumber,
  mlirBlockArgumentGetOwner,
  mlirOpResultGetOwner,
  mlirOpResultGetResultNumber,
  mlirValueDump,
  mlirValueGetFirstUse,
  mlirValueGetType,
  mlirValueIsABlockArgument,
  mlirValueIsAOpResult,
  mlirValuePrint,
  mlirValuePrintAsOperand,
  mlirValueReplaceAllUsesOfWith,
  mlirValueSetType
}
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

given ValueApi with
  extension (value: Value)
    inline def getType(
      using arena: Arena
    ): Type = Type(mlirValueGetType(arena, value.segment))
    inline def getFirstUse(
      using arena: Arena
    ): Type = Type(mlirValueGetFirstUse(arena, value.segment))
    inline def setType(tpe:            Type):  Unit          = mlirValueSetType(value.segment, tpe.segment)
    inline def dump():                         Unit          = mlirValueDump(value.segment)
    inline def print(
      callback:    String => Unit
    )(
      using arena: Arena
    ): Unit = mlirValuePrint(value.segment, callback.stringToStringCallback.segment, MemorySegment.NULL)
    inline def printAsOperand(
      state:       AsmState,
      callback:    String => Unit
    )(
      using arena: Arena
    ): Unit =
      mlirValuePrintAsOperand(value.segment, state.segment, callback.stringToStringCallback.segment, MemorySegment.NULL)
    inline def replaceAllUsesOfWith(w: Value): Unit          = mlirValueReplaceAllUsesOfWith(value.segment, w.segment)
    inline def isOpResult:                     Boolean       = mlirValueIsAOpResult(value.segment)
    inline def isBlockArgument:                Boolean       = mlirValueIsABlockArgument(value.segment)
    inline def opResultGetOwner(
      using arena: Arena
    ): Operation = Operation(mlirOpResultGetOwner(arena, value.segment))
    inline def opResultGetResultNumber:        Long          = mlirOpResultGetResultNumber(value.segment)
    inline def blockArgumentGetOwner(
      using arena: Arena
    ): Block = Block(mlirBlockArgumentGetOwner(arena, value.segment))
    inline def blockArgumentGetArgNumber:      Long          = mlirBlockArgumentGetArgNumber(value.segment)
    inline def segment:                        MemorySegment = value._segment
    inline def sizeOf:                         Int           = MlirValue.sizeof().toInt
end given
