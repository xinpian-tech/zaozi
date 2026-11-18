// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.diagnostic.Diagnostic
import org.llvm.mlir.scalalib.capi.support.{*, given}

given ContextApi with
  inline def contextCreate(
    using zone: Zone
  ): Context =
    Context(nativeResult[MlirStructs.MlirContext](out => mlirApi.mlirContextCreate()(out)))

  inline def contextCreateWithThreading(
    threadingEnabled: Boolean
  )(
    using zone:       Zone
  ): Context =
    Context(nativeResult[MlirStructs.MlirContext](out => mlirApi.mlirContextCreateWithThreading(threadingEnabled)(out)))

  inline def contextCreateWithRegistry(
    registry:         DialectRegistry,
    threadingEnabled: Boolean
  )(
    using zone:       Zone
  ): Context =
    Context(
      nativeResult[MlirStructs.MlirContext](out =>
        mlirApi.mlirContextCreateWithRegistry(registry.native, threadingEnabled)(out)
      )
    )

  extension (context: Context)
    inline def getOrLoadDialect(
      name:       String
    )(
      using zone: Zone
    ): Dialect = Dialect(
      nativeResult[MlirStructs.MlirDialect](out =>
        mlirApi.mlirContextGetOrLoadDialect(context.native, name.toStringRef.native)(out)
      )
    )
    inline def destroy():                                        Unit = mlirApi.mlirContextDestroy(context.native)
    inline def allowUnregisteredDialects(allow: Boolean):        Unit =
      mlirApi.mlirContextSetAllowUnregisteredDialects(context.native, allow)
    inline def appendDialectRegistry(registry: DialectRegistry): Unit =
      mlirApi.mlirContextAppendDialectRegistry(context.native, registry.native)
    inline def enableMultithreading(enable: Boolean):            Unit =
      mlirApi.mlirContextEnableMultithreading(context.native, enable)
    inline def loadAllAvailableDialects():                       Unit =
      mlirApi.mlirContextLoadAllAvailableDialects(context.native)
    inline def setThreadPool(threadPool: LlvmThreadPool):        Unit =
      mlirApi.mlirContextSetThreadPool(context.native, threadPool.native)

    def attachDiagnosticHandler(
      handler:    Diagnostic => Boolean
    )(
      using zone: Zone
    ): Long =
      val state = NativeCallbacks.retain(handler)
      try
        mlirApi
          .mlirContextAttachDiagnosticHandler(
            context.native,
            CallbackFunctions.diagnostic(),
            state,
            CallbackFunctions.release()
          )
          .toLong
      catch
        case t: Throwable =>
          NativeCallbacks.release(state)
          throw t

    inline def detachDiagnosticHandler(id: Long): Unit =
      mlirApi.mlirContextDetachDiagnosticHandler(context.native, id.toULong)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirContext].toInt

end given
