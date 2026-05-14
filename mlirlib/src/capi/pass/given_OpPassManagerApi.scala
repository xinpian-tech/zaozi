// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.pass

import org.llvm.mlir.*
import org.llvm.mlir.CAPI.{
  mlirOpPassManagerAddOwnedPass,
  mlirOpPassManagerAddPipeline,
  mlirOpPassManagerGetNestedUnder,
  mlirParsePassPipeline,
  mlirPrintPassPipeline
}
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

given OpPassManagerApi with
  extension (opPassManager: OpPassManager)
    inline def getNestedUnder(
      passManager:   PassManager,
      operationName: String
    )(
      using arena:   Arena
    ): OpPassManager =
      OpPassManager(
        mlirOpPassManagerGetNestedUnder(arena, passManager.segment, operationName.toStringRef.segment),
        passManager._callbackArenas,
        passManager._ownedPasses
      )
    inline def addPipeline(
      pipelineElements: String,
      callback:         String => Unit
    )(
      using arena:      Arena
    ): LogicalResult =
      LogicalResult(
        mlirOpPassManagerAddPipeline(
          arena,
          opPassManager.segment,
          pipelineElements.toStringRef.segment,
          callback.stringToStringCallback.segment,
          MemorySegment.NULL
        )
      )
    inline def parsePassPipeline(
      pipeline:    String,
      callback:    String => Unit
    )(
      using arena: Arena
    ): LogicalResult = LogicalResult(
      mlirParsePassPipeline(
        arena,
        opPassManager.segment,
        pipeline.toStringRef.segment,
        callback.stringToStringCallback.segment,
        MemorySegment.NULL
      )
    )
    inline def addOwnedPass(
      pass: Pass
    ): Unit =
      mlirOpPassManagerAddOwnedPass(opPassManager.segment, pass.segment)
      val a = pass._callbackArena
      if a != null then opPassManager._callbackArenas.add(a)
      opPassManager._ownedPasses.add(pass)
    inline def printPassPipeline(
      callback:    String => Unit
    )(
      using arena: Arena
    ): Unit = mlirPrintPassPipeline(opPassManager.segment, callback.stringToStringCallback.segment, MemorySegment.NULL)
    inline def segment: MemorySegment = opPassManager._segment
    inline def sizeOf:  Int           = MlirOpPassManager.sizeof().toInt
end given
