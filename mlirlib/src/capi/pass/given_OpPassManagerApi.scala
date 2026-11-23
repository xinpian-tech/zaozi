// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.pass

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given OpPassManagerApi with
  extension (opPassManager: OpPassManager)
    inline def getNestedUnder(
      operationName: String
    )(
      using zone:    Zone
    ): OpPassManager =
      OpPassManager(
        nativeResult[MlirStructs.MlirOpPassManager](out =>
          mlirApi.mlirOpPassManagerGetNestedUnder(opPassManager.native, operationName.toStringRef.native)(out)
        ),
        opPassManager._callbackStates,
        opPassManager._ownedPasses
      )
    inline def addPipeline(
      pipelineElements: String,
      callback:         String => Unit
    )(
      using zone:       Zone
    ): LogicalResult =
      LogicalResult(
        callback.stringToStringCallback.use { (fn, data) =>
          nativeResult[MlirStructs.MlirLogicalResult](out =>
            mlirApi.mlirOpPassManagerAddPipeline(opPassManager.native, pipelineElements.toStringRef.native, fn, data)(
              out
            )
          )
        }
      )
    inline def parsePassPipeline(
      pipeline:   String,
      callback:   String => Unit
    )(
      using zone: Zone
    ): LogicalResult = LogicalResult(
      callback.stringToStringCallback.use { (fn, data) =>
        nativeResult[MlirStructs.MlirLogicalResult](out =>
          mlirApi.mlirParsePassPipeline(opPassManager.native, pipeline.toStringRef.native, fn, data)(out)
        )
      }
    )
    inline def addOwnedPass(
      pass: Pass
    ): Unit =
      mlirApi.mlirOpPassManagerAddOwnedPass(opPassManager.native, pass.native)
      val a = pass._callbackState
      if a != null then opPassManager._callbackStates.add(a)
      opPassManager._ownedPasses.add(pass)
    inline def printPassPipeline(
      callback:   String => Unit
    )(
      using zone: Zone
    ): Unit = callback.stringToStringCallback.use { (fn, data) =>
      mlirApi.mlirPrintPassPipeline(opPassManager.native, fn, data)
    }

    inline def sizeOf: Int = sizeof[MlirStructs.MlirOpPassManager].toInt
end given
