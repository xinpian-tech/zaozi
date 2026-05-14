// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.pass

import org.llvm.mlir.*
import org.llvm.mlir.CAPI.{
  mlirPassManagerAddOwnedPass,
  mlirPassManagerCreate,
  mlirPassManagerCreateOnOperation,
  mlirPassManagerDestroy,
  mlirPassManagerEnableIRPrinting,
  mlirPassManagerEnableVerifier,
  mlirPassManagerGetAsOpPassManager,
  mlirPassManagerGetNestedUnder,
  mlirPassManagerRunOnOp
}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

given PassManagerApi with
  inline def passManagerCreate(
    using arena: Arena,
    context:     Context
  ): PassManager =
    PassManager(mlirPassManagerCreate(arena, context.segment))
  inline def passManagerCreateOnOperation(
    name:        String
  )(
    using arena: Arena,
    context:     Context
  ): PassManager = PassManager(mlirPassManagerCreateOnOperation(arena, context.segment, name.toStringRef.segment))
  extension (passManager: PassManager)
    inline def getAsOpPassManager(
      using arena: Arena
    ): OpPassManager =
      OpPassManager(
        mlirPassManagerGetAsOpPassManager(arena, passManager.segment),
        passManager._callbackArenas,
        passManager._ownedPasses
      )
    inline def runOnOp(
      operation:   Operation
    )(
      using arena: Arena
    ): LogicalResult =
      // Clear every owned pass's `_lastFailure` slot before invoking the native
      // pass manager so a stale `UpcallFailure` from a prior `runOnOp` cannot
      // be surfaced as the cause of this run's native failure.
      val pre    = passManager._ownedPasses.iterator()
      while pre.hasNext do pre.next()._lastFailure.set(null)
      val result = LogicalResult(mlirPassManagerRunOnOp(arena, passManager.segment, operation.segment))
      if result.failed then
        passManager.lastUpcallFailure match
          case Some(f) => throw new PassRunFailure(f)
          case None    => result
      else result
    inline def getNestedUnder(
      operationName: String
    )(
      using arena:   Arena
    ): OpPassManager = OpPassManager(
      mlirPassManagerGetNestedUnder(arena, passManager.segment, operationName.toStringRef.segment),
      passManager._callbackArenas,
      passManager._ownedPasses
    )
    inline def destroy(): Unit =
      // Destroy the native pass manager FIRST so it can run any final
      // callbacks while the callback arenas are still alive. Then close every
      // retained callback arena. Use a try/finally so a partial-failure
      // mid-list still closes the rest.
      try mlirPassManagerDestroy(passManager.segment)
      finally
        val it = passManager._callbackArenas.iterator()
        while it.hasNext do
          val a = it.next()
          try a.close()
          catch case _: Throwable => ()
        passManager._callbackArenas.clear()
    inline def enableIRPrinting(
      printBeforeAll:          Boolean,
      printAfterAll:           Boolean,
      printModuleScope:        Boolean,
      printAfterOnlyOnChange:  Boolean,
      printAfterOnlyOnFailure: Boolean,
      flags:                   OpPrintingFlags,
      treePrintingPath:        String
    )(
      using arena:             Arena
    ): Unit = mlirPassManagerEnableIRPrinting(
      passManager.segment,
      printBeforeAll,
      printAfterAll,
      printModuleScope,
      printAfterOnlyOnChange,
      printAfterOnlyOnFailure,
      flags.segment,
      treePrintingPath.toStringRef.segment
    )
    inline def enableVerifier(enable: Boolean): Unit                  = mlirPassManagerEnableVerifier(passManager.segment, enable)
    inline def addOwnedPass(
      pass: Pass
    ): Unit =
      mlirPassManagerAddOwnedPass(passManager.segment, pass.segment)
      val a = pass._callbackArena
      if a != null then passManager._callbackArenas.add(a)
      passManager._ownedPasses.add(pass)
    inline def lastUpcallFailure:               Option[UpcallFailure] =
      val it = passManager._ownedPasses.iterator()
      var found: UpcallFailure | Null = null
      while it.hasNext do
        val p = it.next()
        val f = p._lastFailure.get()
        if f != null then if found == null || f.timestampNs > found.timestampNs then found = f
      Option(found)
    inline def segment:                         MemorySegment         = passManager._segment
    inline def sizeOf:                          Int                   = MlirPassManager.sizeof().toInt
end given
