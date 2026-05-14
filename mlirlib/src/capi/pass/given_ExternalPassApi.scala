// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.pass

import org.llvm.mlir.*
import org.llvm.mlir.CAPI.{mlirCreateExternalPass, mlirExternalPassSignalFailure}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}

import java.lang.foreign.{Arena, MemorySegment}
import java.util.concurrent.atomic.AtomicReference

given PassApi with
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
  ): Pass =
    // ofShared rather than ofConfined: MLIR's pass manager may dispatch the
    // upcall on a worker thread when nested pass pipelines run in parallel
    // (mlir::PassManager::run uses MLIRContext::isMultithreadingEnabled to
    // dispatch op-level passes via the context's thread pool). A confined
    // arena rejects access from any thread other than its creator, so the
    // upcall stubs must live in a shared arena to survive that dispatch.
    val callbackArena           = Arena.ofShared()
    val initializeFailureResult = MlirLogicalResult.allocate(callbackArena)
    val lastFailure: AtomicReference[UpcallFailure | Null] = new AtomicReference()

    def recordFailure(t: Throwable, op: MemorySegment, ep: MemorySegment): Unit =
      lastFailure.set(new UpcallFailure(t, op, ep, System.nanoTime()))
      // Void upcalls (construct/destruct/clone) have no signalFailure path
      // back to the C++ pass manager; surface their failures to stderr as a
      // minimum so users do not see silently-lost exceptions.
      System.err.println(s"[zaozi:external-pass:upcall] ${t.getClass.getName}: ${t.getMessage}")
      t.printStackTrace(System.err)

    val callbacksSegment = MlirExternalPassCallbacks.allocate(arena)
    MlirExternalPassCallbacks.construct(
      callbacksSegment,
      MlirExternalPassCallbacks.construct.allocate(
        (nil: MemorySegment) =>
          try constructCallback()
          catch case t: Throwable => recordFailure(t, MemorySegment.NULL, MemorySegment.NULL), callbackArena
      )
    )
    MlirExternalPassCallbacks.destruct(
      callbacksSegment,
      MlirExternalPassCallbacks.destruct.allocate(
        (nil: MemorySegment) =>
          try destructCallback()
          catch case t: Throwable => recordFailure(t, MemorySegment.NULL, MemorySegment.NULL), callbackArena
      )
    )
    initializeCallback.foreach(cb =>
      MlirExternalPassCallbacks.initialize(
        callbacksSegment,
        MlirExternalPassCallbacks.initialize.allocate(
          (context: MemorySegment, nil: MemorySegment) =>
            try cb(Context(context)).segment
            catch
              case t: Throwable =>
                recordFailure(t, MemorySegment.NULL, MemorySegment.NULL)
                initializeFailureResult,
          callbackArena
        )
      )
    )
    MlirExternalPassCallbacks.`clone`(
      callbacksSegment,
      MlirExternalPassCallbacks.`clone`.allocate(
        (nil: MemorySegment) =>
          try cloneCallback()
          catch case t: Throwable => recordFailure(t, MemorySegment.NULL, MemorySegment.NULL)
            // mlir-c returns null userData for cloned external passes; see
            // mlir/lib/CAPI/IR/Pass.cpp ExternalPass::clonePass.
          MemorySegment.NULL
        ,
        callbackArena
      )
    )
    MlirExternalPassCallbacks.run(
      callbacksSegment,
      MlirExternalPassCallbacks.run.allocate(
        (operation: MemorySegment, externalPass: MemorySegment, userData: MemorySegment) =>
          try runCallback(Operation(operation), ExternalPass(externalPass))
          catch
            case t: Throwable =>
              recordFailure(t, operation, externalPass)
              mlirExternalPassSignalFailure(externalPass),
        callbackArena
      )
    )
    Pass(
      mlirCreateExternalPass(
        arena,
        passId.segment,
        name.toStringRef.segment,
        argument.toStringRef.segment,
        description.toStringRef.segment,
        opName.toStringRef.segment,
        dependentDialects.size,
        dependentDialects.toMlirArray,
        callbacksSegment,
        MemorySegment.NULL
      ),
      callbackArena,
      lastFailure
    )

  extension (pass: Pass)
    inline def segment:           MemorySegment         = pass._segment
    inline def sizeOf:            Int                   = MlirPass.sizeof().toInt
    inline def destroy():         Unit                  =
      val a = pass._callbackArena
      if a != null then a.close()
    inline def lastUpcallFailure: Option[UpcallFailure] =
      Option(pass._lastFailure.get())
end given

given ExternalPassApi with
  extension (pass: ExternalPass)
    inline def signalFailure(): Unit          = mlirExternalPassSignalFailure(pass.segment)
    inline def segment:         MemorySegment = pass._segment
    inline def sizeOf:          Int           = MlirExternalPass.sizeof().toInt
end given

given ExternalPassCallbacksApi with
  extension (pass: ExternalPassCallbacks)
    inline def signalFailure(): Unit          = mlirExternalPassSignalFailure(pass.segment)
    inline def segment:         MemorySegment = pass._segment
    inline def sizeOf:          Int           = MlirExternalPassCallbacks.sizeof().toInt
end given
