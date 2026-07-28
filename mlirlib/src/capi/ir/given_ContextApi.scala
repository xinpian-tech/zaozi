// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.*
import org.llvm.mlir.CAPI.{
  mlirContextAppendDialectRegistry,
  mlirContextAttachDiagnosticHandler,
  mlirContextCreate,
  mlirContextCreateWithRegistry,
  mlirContextCreateWithThreading,
  mlirContextDestroy,
  mlirContextDetachDiagnosticHandler,
  mlirContextEnableMultithreading,
  mlirContextGetOrLoadDialect,
  mlirContextLoadAllAvailableDialects,
  mlirContextSetAllowUnregisteredDialects,
  mlirContextSetThreadPool
}
import org.llvm.mlir.scalalib.capi.diagnostic.Diagnostic
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

given ContextApi with
  inline def contextCreate(
    using arena: Arena
  ): Context =
    Context(mlirContextCreate(arena))

  inline def contextCreateWithThreading(
    threadingEnabled: Boolean
  )(
    using arena:      Arena
  ): Context =
    Context(mlirContextCreateWithThreading(arena, threadingEnabled))

  inline def contextCreateWithRegistry(
    registry:         DialectRegistry,
    threadingEnabled: Boolean
  )(
    using arena:      Arena
  ): Context =
    Context(mlirContextCreateWithRegistry(arena, registry.segment, threadingEnabled))

  extension (context: Context)
    inline def getOrLoadDialect(
      name:        String
    )(
      using arena: Arena
    ): Dialect = Dialect(mlirContextGetOrLoadDialect(arena, context.segment, name.toStringRef.segment))
    inline def destroy():                                        Unit = mlirContextDestroy(context.segment)
    inline def allowUnregisteredDialects(allow: Boolean):        Unit =
      mlirContextSetAllowUnregisteredDialects(context.segment, allow)
    inline def appendDialectRegistry(registry: DialectRegistry): Unit =
      mlirContextAppendDialectRegistry(context.segment, registry.segment)
    inline def enableMultithreading(enable: Boolean):            Unit =
      mlirContextEnableMultithreading(context.segment, enable)
    inline def loadAllAvailableDialects():                       Unit =
      mlirContextLoadAllAvailableDialects(context.segment)
    inline def setThreadPool(threadPool: LlvmThreadPool):        Unit =
      mlirContextSetThreadPool(context.segment, threadPool.segment)

    def attachDiagnosticHandler(
      handler:     Diagnostic => Boolean
    )(
      using arena: Arena
    ): Long =
      // MLIR handler protocol: a success result consumes the diagnostic, failure propagates it.
      val consumed  = MlirLogicalResult.allocate(arena)
      MlirLogicalResult.value(consumed, 1.toByte)
      val propagate = MlirLogicalResult.allocate(arena)
      val stub      = MlirDiagnosticHandler.allocate(
        // A Throwable crossing this native upcall boundary aborts the JVM.
        (diagnosticSegment: MemorySegment, _userData: MemorySegment) =>
          try if handler(Diagnostic(diagnosticSegment)) then consumed else propagate
          catch
            case t: Throwable =>
              System.err.println(s"diagnostic handler threw ${t.getClass.getName}: ${t.getMessage}")
              propagate
        ,
        arena
      )
      mlirContextAttachDiagnosticHandler(context.segment, stub, MemorySegment.NULL, MemorySegment.NULL)

    inline def detachDiagnosticHandler(id: Long): Unit =
      mlirContextDetachDiagnosticHandler(context.segment, id)

    inline def segment: MemorySegment = context._segment
    inline def sizeOf:  Int           = MlirContext.sizeof().toInt

end given
