// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.pass

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}

given PassManagerApi with
  inline def passManagerCreate(
    using zone: Zone,
    context:    Context
  ): PassManager =
    PassManager(nativeResult[MlirStructs.MlirPassManager](out => mlirApi.mlirPassManagerCreate(context.native)(out)))
  inline def passManagerCreateOnOperation(
    name:       String
  )(
    using zone: Zone,
    context:    Context
  ): PassManager = PassManager(
    nativeResult[MlirStructs.MlirPassManager](out =>
      mlirApi.mlirPassManagerCreateOnOperation(context.native, name.toStringRef.native)(out)
    )
  )
  extension (passManager: PassManager)
    inline def getAsOpPassManager(
      using zone: Zone
    ): OpPassManager =
      OpPassManager(
        nativeResult[MlirStructs.MlirOpPassManager](out =>
          mlirApi.mlirPassManagerGetAsOpPassManager(passManager.native)(out)
        ),
        passManager._callbackStates,
        passManager._ownedPasses
      )
    def runOnOp(
      operation:  Operation
    )(
      using zone: Zone
    ): LogicalResult =
      // Clear every owned pass's `_lastFailure` slot before invoking the native
      // pass manager so a stale `UpcallFailure` from a prior `runOnOp` cannot
      // be surfaced as the cause of this run's native failure.
      val pre       = passManager._ownedPasses.iterator()
      while pre.hasNext do pre.next()._lastFailure.set(null)
      val callbacks = passManager._callbackStates.iterator()
      while callbacks.hasNext do NativeCallbacks.entry[Any](callbacks.next()).failure = null
      val result    = LogicalResult(
        nativeResult[MlirStructs.MlirLogicalResult](out =>
          PassEntryPoints.withInvocation(passManager._ownedPasses, zone) {
            mlirApi.mlirPassManagerRunOnOp(passManager.native, operation.native)(out)
          }
        )
      )
      val completed = passManager._callbackStates.iterator()
      while completed.hasNext do
        val error = NativeCallbacks.entry[Any](completed.next()).failure
        if error != null then throw error
      if result.failed then
        passManager.lastUpcallFailure match
          case Some(f) => throw new PassRunFailure(f)
          case None    => result
      else result
    inline def getNestedUnder(
      operationName: String
    )(
      using zone:    Zone
    ): OpPassManager = OpPassManager(
      nativeResult[MlirStructs.MlirOpPassManager](out =>
        mlirApi.mlirPassManagerGetNestedUnder(passManager.native, operationName.toStringRef.native)(out)
      ),
      passManager._callbackStates,
      passManager._ownedPasses
    )
    inline def destroy():                       Unit                  =
      try mlirApi.mlirPassManagerDestroy(passManager.native)
      finally
        val it = passManager._callbackStates.iterator()
        while it.hasNext do NativeCallbacks.release(it.next())
        passManager._callbackStates.clear()
        passManager._ownedPasses.clear()
    inline def enableIRPrinting(
      printBeforeAll:          Boolean,
      printAfterAll:           Boolean,
      printModuleScope:        Boolean,
      printAfterOnlyOnChange:  Boolean,
      printAfterOnlyOnFailure: Boolean,
      flags:                   OpPrintingFlags,
      treePrintingPath:        String
    )(
      using zone:              Zone
    ): Unit = mlirApi.mlirPassManagerEnableIRPrinting(
      passManager.native,
      printBeforeAll,
      printAfterAll,
      printModuleScope,
      printAfterOnlyOnChange,
      printAfterOnlyOnFailure,
      flags.native,
      treePrintingPath.toStringRef.native
    )
    inline def enableVerifier(enable: Boolean): Unit                  = mlirApi.mlirPassManagerEnableVerifier(passManager.native, enable)
    inline def addOwnedPass(
      pass: Pass
    ): Unit =
      mlirApi.mlirPassManagerAddOwnedPass(passManager.native, pass.native)
      val a = pass._callbackState
      if a != null then passManager._callbackStates.add(a)
      passManager._ownedPasses.add(pass)
    inline def lastUpcallFailure:               Option[UpcallFailure] =
      val it = passManager._ownedPasses.iterator()
      var found: UpcallFailure | Null = null
      while it.hasNext do
        val p = it.next()
        val f = p._lastFailure.get()
        if f != null then if found == null || f.timestampNs > found.timestampNs then found = f
      Option(found)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirPassManager].toInt
end given
