// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.pass

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

final class UpcallFailure(
  val cause:       Throwable,
  val opSegment:   MemorySegment,
  val passSegment: MemorySegment,
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
  val _segment:       MemorySegment,
  val _callbackArena: Arena | Null = null,
  val _lastFailure:   java.util.concurrent.atomic.AtomicReference[UpcallFailure | Null] =
    new java.util.concurrent.atomic.AtomicReference())
trait PassApi extends HasSegment[Pass] with HasSizeOf[Pass]:
  // Not inlined: the body references `MlirExternalPassCallbacks.`clone`` and
  // similarly-named jextract members that collide with Java's `Object.clone()`.
  // Inlining causes Scala 3 to re-resolve `clone` at each call site, which
  // intermittently fails cold-compile with `MlirExternalPassCallbacks.clone#Function`
  // path-dependent typing. Keeping it a regular method confines resolution to
  // this trait's defining context.
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
    using arena:        Arena
  ): Pass

  extension (pass: Pass)
    // Closes the Pass's callback arena (if any). Safe to call multiple times
    // on the same Pass — closing an already-closed Arena is a no-op here
    // because the field is read once and not nulled out; callers should not
    // double-destroy in practice.
    inline def destroy(): Unit

end PassApi

class ExternalPass(val _segment: MemorySegment)
trait ExternalPassApi extends HasSegment[ExternalPass] with HasSizeOf[ExternalPass]

// `_callbackArenas` holds the per-Pass callback arenas of every external
// `Pass` transferred to this `PassManager` via `addOwnedPass`. The PassManager
// closes these arenas in `destroy()` after `mlirPassManagerDestroy` returns,
// so external-pass callers must NOT call `pass.destroy()` after ownership
// transfer — the PassManager owns the callback lifetime from that point.
class PassManager(
  val _segment:        MemorySegment,
  val _callbackArenas: java.util.ArrayList[Arena] = new java.util.ArrayList(),
  val _ownedPasses:    java.util.ArrayList[Pass] = new java.util.ArrayList())
trait PassManagerApi extends HasSegment[PassManager] with HasSizeOf[PassManager]:
  inline def passManagerCreate(
    using arena: Arena,
    context:     Context
  ): PassManager

  inline def passManagerCreateOnOperation(
    name:        String
  )(
    using arena: Arena,
    context:     Context
  ): PassManager

  extension (passManager: PassManager)
    inline def getAsOpPassManager(
      using arena: Arena
    ):                                          OpPassManager
    inline def runOnOp(
      operation:   Operation
    )(
      using arena: Arena
    ):                                          LogicalResult
    inline def getNestedUnder(
      operationName: String
    )(
      using arena:   Arena
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
      using arena:             Arena
    ):                                          Unit
    inline def enableVerifier(enable: Boolean): Unit
    inline def addOwnedPass(
      pass: Pass
    ):                                          Unit
    inline def lastUpcallFailure:               Option[UpcallFailure]

end PassManagerApi

// `_callbackArenas` is shared with the parent `PassManager` that this
// `OpPassManager` is a view of; a Pass added via `OpPassManager.addOwnedPass`
// ultimately lives in the parent PassManager's pass tree, so its callback
// arena must be retained on the parent's collection.
class OpPassManager(
  val _segment:        MemorySegment,
  val _callbackArenas: java.util.ArrayList[Arena],
  val _ownedPasses:    java.util.ArrayList[Pass])
trait OpPassManagerApi extends HasSegment[OpPassManager] with HasSizeOf[OpPassManager]:
  extension (opPassManager: OpPassManager)
    inline def getNestedUnder(
      passManager:   PassManager,
      operationName: String
    )(
      using arena:   Arena
    ): OpPassManager
    inline def addPipeline(
      pipelineElements: String,
      callback:         String => Unit
    )(
      using arena:      Arena
    ): LogicalResult
    inline def parsePassPipeline(
      pipeline:    String,
      callback:    String => Unit
    )(
      using arena: Arena
    ): LogicalResult
    inline def addOwnedPass(
      pass: Pass
    ): Unit
end OpPassManagerApi

class ExternalPassCallbacks(val _segment: MemorySegment)
trait ExternalPassCallbacksApi extends HasSegment[ExternalPassCallbacks] with HasSizeOf[ExternalPassCallbacks]
