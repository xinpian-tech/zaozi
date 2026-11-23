// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.pass

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}

final class UpcallFailure(
  val cause:       Throwable,
  val opSegment:   Ptr[Byte],
  val passSegment: Ptr[Byte],
  val timestampNs: Long)

// Wraps an `UpcallFailure` captured during external-pass execution and
// re-throws it from `PassManager.runOnOp` so the Scala caller observes the
// original throwable as a normal exception instead of a silent native
// failure. The `cause` chain preserves the user's original exception.
final class PassRunFailure(val failure: UpcallFailure)
    extends RuntimeException(
      s"external pass upcall threw ${failure.cause.getClass.getName}: ${failure.cause.getMessage}",
      failure.cause
    )

class Pass(
  handle:             Ptr[MlirStructs.MlirPass],
  val _callbackState: Ptr[Byte] = null,
  val _lastFailure:   java.util.concurrent.atomic.AtomicReference[UpcallFailure | Null] =
    new java.util.concurrent.atomic.AtomicReference())
    extends NativeHandle(handle)
trait PassApi extends HasSizeOf[Pass]:
  def createExternalPass(
    passId:             TypeID,
    name:               String,
    argument:           String,
    description:        String,
    opName:             String,
    dependentDialects:  Seq[DialectHandle],
    constructCallback:  () => Unit,
    destructCallback:   () => Unit,
    initializeCallback: Option[Context => LogicalResult],
    cloneCallback:      () => Unit,
    runCallback:        (Operation, ExternalPass) => Unit
  )(
    using zone:         Zone
  ): Pass

  extension (pass: Pass)
    def destroy(
    )(
      using Zone,
      Context
    ): Unit

end PassApi

class ExternalPass(handle: Ptr[MlirStructs.MlirExternalPass]) extends NativeHandle(handle)
trait ExternalPassApi                                         extends HasSizeOf[ExternalPass]

class PassManager(
  handle:              Ptr[MlirStructs.MlirPassManager],
  val _callbackStates: java.util.ArrayList[Ptr[Byte]] = new java.util.ArrayList(),
  val _ownedPasses:    java.util.ArrayList[Pass] = new java.util.ArrayList())
    extends NativeHandle(handle)
trait PassManagerApi extends HasSizeOf[PassManager]:
  inline def passManagerCreate(
    using zone: Zone,
    context:    Context
  ): PassManager

  inline def passManagerCreateOnOperation(
    name:       String
  )(
    using zone: Zone,
    context:    Context
  ): PassManager

  extension (passManager: PassManager)
    inline def getAsOpPassManager(
      using zone: Zone
    ):                                          OpPassManager
    def runOnOp(
      operation:  Operation
    )(
      using zone: Zone
    ):                                          LogicalResult
    inline def getNestedUnder(
      operationName: String
    )(
      using zone:    Zone
    ):                                          OpPassManager
    inline def destroy():                       Unit
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
    ):                                          Unit
    inline def enableVerifier(enable: Boolean): Unit
    inline def addOwnedPass(
      pass: Pass
    ):                                          Unit
    inline def lastUpcallFailure:               Option[UpcallFailure]

end PassManagerApi

class OpPassManager(
  handle:              Ptr[MlirStructs.MlirOpPassManager],
  val _callbackStates: java.util.ArrayList[Ptr[Byte]],
  val _ownedPasses:    java.util.ArrayList[Pass])
    extends NativeHandle(handle)
trait OpPassManagerApi extends HasSizeOf[OpPassManager]:
  extension (opPassManager: OpPassManager)
    inline def getNestedUnder(
      operationName: String
    )(
      using zone:    Zone
    ): OpPassManager
    inline def addPipeline(
      pipelineElements: String,
      callback:         String => Unit
    )(
      using zone:       Zone
    ): LogicalResult
    inline def parsePassPipeline(
      pipeline:   String,
      callback:   String => Unit
    )(
      using zone: Zone
    ): LogicalResult
    inline def addOwnedPass(
      pass: Pass
    ): Unit
end OpPassManagerApi

class ExternalPassCallbacks(handle: Ptr[MlirStructs.MlirExternalPassCallbacks]) extends NativeHandle(handle)
trait ExternalPassCallbacksApi                                                  extends HasSizeOf[ExternalPassCallbacks]
