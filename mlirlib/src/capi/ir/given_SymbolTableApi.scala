// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.*
import org.llvm.mlir.CAPI.{mlirSymbolTableCreate, mlirSymbolTableDestroy, mlirSymbolTableLookup}
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

given SymbolTableApi with
  inline def symbolTableCreate(
    operation:   Operation
  )(
    using arena: Arena
  ): SymbolTable = new SymbolTable(mlirSymbolTableCreate(arena, operation.segment))

  extension (symbolTable: SymbolTable)
    inline def lookup(
      name:        String
    )(
      using arena: Arena
    ): Operation = new Operation(mlirSymbolTableLookup(arena, symbolTable.segment, name.toStringRef.segment))
    inline def destroy(): Unit          = mlirSymbolTableDestroy(symbolTable.segment)
    inline def segment:   MemorySegment = symbolTable._segment
    inline def sizeOf:    Int           = MlirSymbolTable.sizeof().toInt
end given
