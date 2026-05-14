// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.mlirlib.tests

import org.llvm.mlir.CAPI.mlirTypeIDCreate
import org.llvm.mlir.{MlirExternalPassCallbacks, MlirLogicalResult}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.pass.{*, given}
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.{Arena, ValueLayout}
import java.util.concurrent.atomic.AtomicInteger
import utest.*

// Builds a trivial external pass through the public PassApi.createExternalPass,
// transfers it to a PassManager via addOwnedPass, exits the transient caller
// arena scope, then invokes the pass via pm.runOnOp AFTER the caller arena is
// closed. The five upcall stubs (construct/destruct/initialize/clone/run) live
// on a Pass-owned arena that PassManager retains in _callbackArenas.
//
// Cleanup is structured so every native resource (outerArena, Context, Module,
// PassManager) has its own try/finally and gets destroyed even when an
// assertion fails. Post-destroy assertions run inside the inner finally so
// they still execute (and fail the test) on the failure path.
object ExternalPassLifetimeSpec extends TestSuite:

  private def isOpen(arena: Arena): Boolean =
    try
      arena.allocate(8L)
      true
    catch case _: IllegalStateException => false

  private def runRealLifetimeCheck(): Unit =
    val outerArena = Arena.ofConfined()
    try
      given Arena = outerArena
      val context = summon[ContextApi].contextCreate
      try
        given Context       = context
        // The pass operates on builtin.module; allow unregistered dialects so
        // the empty module passes verification without dialect registration.
        context.allowUnregisteredDialects(true)
        val unknownLocation = summon[LocationApi].locationUnknownGet
        val module          = summon[ModuleApi].moduleCreateEmpty(unknownLocation)
        try
          val moduleOp = module.getOperation
          // Top-level PM defaults to anchoring on builtin.module.
          val pm       = summon[PassManagerApi].passManagerCreate

          // AtomicInteger counters so the assertions can read values even if
          // MLIR invokes the upcall on a different native thread.
          val constructCounter = new AtomicInteger(0)
          val destructCounter  = new AtomicInteger(0)
          val runCounter       = new AtomicInteger(0)

          // Captured from inside the transient scope. Local strong reference
          // alone is NOT proof of retention by pm._callbackArenas; that
          // contract is verified by a direct `contains` assertion below and
          // by the close-on-pm.destroy() assertion further down.
          var capturedCallbackArena: Arena = null

          try
            // Open a transient caller arena. Create the external pass on it,
            // transfer ownership to pm, then close it.
            val transientArena = Arena.ofConfined()
            try
              given Arena   = transientArena
              // mlirTypeIDCreate requires an 8-byte-aligned unique pointer;
              // the address identifies the pass class to MLIR.
              val uniquePtr = transientArena.allocate(ValueLayout.JAVA_LONG)
              val passId    = TypeID(mlirTypeIDCreate(transientArena, uniquePtr))
              val pass      = summon[PassApi].createExternalPass(
                passId = passId,
                name = "TestExternalPass",
                argument = "test-external-pass",
                description = "external-pass lifetime fixture",
                // Empty opName makes this a generic (any-op) external pass;
                // MLIR can then schedule it under the top-level PM rooted on
                // builtin.module without nesting errors.
                opName = "",
                dependentDialects = Seq.empty,
                constructCallback = () => constructCounter.incrementAndGet(),
                destructCallback = () => destructCounter.incrementAndGet(),
                initializeCallback = None,
                cloneCallback = () => (),
                runCallback = (_: Operation, _: ExternalPass) => runCounter.incrementAndGet()
              )
              capturedCallbackArena = pass._callbackArena.asInstanceOf[Arena]
              pm.addOwnedPass(pass)
            finally transientArena.close()

            // Post-transfer-and-scope-exit assertions:
            // 1. The Pass exposed a non-null callback arena — the five upcall
            //    stubs were not allocated on the caller arena.
            assert(capturedCallbackArena != null)
            // 2. pm.addOwnedPass appended pass._callbackArena to
            //    pm._callbackArenas. This is the direct retention assertion;
            //    `isOpen(capturedCallbackArena)` alone would not prove it
            //    because the local val keeps the Arena reachable regardless.
            assert(pm._callbackArenas.contains(capturedCallbackArena))
            // 3. The callback arena is still open after the transient
            //    scope's close — sanity, given (2).
            assert(isOpen(capturedCallbackArena))

            // Run the pass on the module operation. Dispatches the `run`
            // upcall; would fault if the upcall stubs lived on the (closed)
            // caller arena.
            val result = pm.runOnOp(moduleOp)
            // 4. Pass ran successfully (no signalFailure dispatched).
            assert(MlirLogicalResult.value(result.segment) == 1.toByte)
            // 5. The run callback fired at least once.
            assert(runCounter.get() >= 1)
          finally
            // pm.destroy() must run even if any assertion above threw.
            try pm.destroy()
            finally
              // Post-destroy assertions; only meaningful if the Pass was
              // ever created.
              if capturedCallbackArena != null then
                // 6. PassManager.destroy closed the retained callback arena
                //    AFTER mlirPassManagerDestroy returned.
                assert(!isOpen(capturedCallbackArena))
                // 7. The destruct callback fired during
                //    mlirPassManagerDestroy (before the callback arena was
                //    closed).
                assert(destructCounter.get() >= 1)
        finally module.destroy()
      finally context.destroy()
    finally outerArena.close()

  private class RunUpcallSentinel(msg: String) extends RuntimeException(msg)

  private def runCaptureAndSurfaceCheck(): Unit =
    val outerArena = Arena.ofConfined()
    try
      given Arena = outerArena
      val context = summon[ContextApi].contextCreate
      try
        given Context       = context
        context.allowUnregisteredDialects(true)
        val unknownLocation = summon[LocationApi].locationUnknownGet
        val module          = summon[ModuleApi].moduleCreateEmpty(unknownLocation)
        try
          val moduleOp = module.getOperation
          val pm       = summon[PassManagerApi].passManagerCreate

          val sentinel = new RunUpcallSentinel("captured-by-test")
          var thrown:        PassRunFailure | Null = null
          var pmRunReturned: Boolean               = false

          try
            val transientArena = Arena.ofConfined()
            try
              given Arena   = transientArena
              val uniquePtr = transientArena.allocate(ValueLayout.JAVA_LONG)
              val passId    = TypeID(mlirTypeIDCreate(transientArena, uniquePtr))
              val pass      = summon[PassApi].createExternalPass(
                passId = passId,
                name = "RunThrowsExternalPass",
                argument = "run-throws-external-pass",
                description = "run upcall throws to verify capture-and-surface",
                opName = "",
                dependentDialects = Seq.empty,
                constructCallback = () => (),
                destructCallback = () => (),
                initializeCallback = None,
                cloneCallback = () => (),
                runCallback = (_: Operation, _: ExternalPass) => throw sentinel
              )
              pm.addOwnedPass(pass)
            finally transientArena.close()

            try
              val _ = pm.runOnOp(moduleOp)
              pmRunReturned = true
            catch case e: PassRunFailure => thrown = e
          finally
            try pm.destroy()
            catch case _: Throwable => ()

          // 1. pm.runOnOp surfaced the captured failure as a thrown
          //    PassRunFailure — it must not have returned a LogicalResult.
          assert(!pmRunReturned)
          assert(thrown != null)
          val thrownFailure = thrown.failure
          // 2. The wrapped UpcallFailure carries the user's original
          //    exception identity, not a wrapped/translated copy.
          assert(thrownFailure.cause eq sentinel)
          // 3. The captured MLIR segments came from the live run-upcall
          //    dispatch, so they cannot be NULL.
          assert(!thrownFailure.opSegment.equals(java.lang.foreign.MemorySegment.NULL))
          assert(!thrownFailure.passSegment.equals(java.lang.foreign.MemorySegment.NULL))
          // 4. RuntimeException cause chain preserves the user throwable so
          //    standard logging frameworks render the original stack.
          assert(thrown.getCause eq sentinel)
        finally module.destroy()
      finally context.destroy()
    finally outerArena.close()

  private def runStaleFailureClearedCheck(): Unit =
    val outerArena = Arena.ofConfined()
    try
      given Arena = outerArena
      val context = summon[ContextApi].contextCreate
      try
        given Context       = context
        context.allowUnregisteredDialects(true)
        val unknownLocation = summon[LocationApi].locationUnknownGet
        val module          = summon[ModuleApi].moduleCreateEmpty(unknownLocation)
        try
          val moduleOp = module.getOperation
          val pm       = summon[PassManagerApi].passManagerCreate

          val sentinel = new RunUpcallSentinel("first-run-sentinel")
          val runCount = new AtomicInteger(0)
          var capturedPass: Pass                  = null
          var firstThrown:  PassRunFailure | Null = null
          var secondResult: LogicalResult | Null  = null
          var secondThrew:  Throwable | Null      = null

          try
            val transientArena = Arena.ofConfined()
            try
              given Arena   = transientArena
              val uniquePtr = transientArena.allocate(ValueLayout.JAVA_LONG)
              val passId    = TypeID(mlirTypeIDCreate(transientArena, uniquePtr))
              val pass      = summon[PassApi].createExternalPass(
                passId = passId,
                name = "StaleFailureExternalPass",
                argument = "stale-failure-external-pass",
                description = "two runs on one PM: throw, then signalFailure-only",
                opName = "",
                dependentDialects = Seq.empty,
                constructCallback = () => (),
                destructCallback = () => (),
                initializeCallback = None,
                cloneCallback = () => (),
                // First run: throw → capture-and-surface produces PassRunFailure.
                // Second run: signalFailure-only → native result is failure but
                // the upcall captured nothing; PassManager.runOnOp must return
                // the failed LogicalResult, NOT re-throw the first-run sentinel.
                runCallback = (_: Operation, ep: ExternalPass) =>
                  runCount.incrementAndGet() match
                    case 1 => throw sentinel
                    case _ => ep.signalFailure()
              )
              capturedPass = pass
              pm.addOwnedPass(pass)
            finally transientArena.close()

            // First run: expect PassRunFailure wrapping the sentinel.
            try pm.runOnOp(moduleOp)
            catch case e: PassRunFailure => firstThrown = e

            // After the first run, the per-Pass slot has the captured failure.
            assert(capturedPass._lastFailure.get() != null)

            // Second run: the slot-clear at the start of runOnOp must null
            // out the captured failure before dispatching, so the second
            // run's native failure is not surfaced as the stale sentinel.
            try secondResult = pm.runOnOp(moduleOp)
            catch case t: Throwable => secondThrew = t
          finally
            try pm.destroy()
            catch case _: Throwable => ()

          // 1. First run produced PassRunFailure(sentinel).
          assert(firstThrown != null)
          assert(firstThrown.failure.cause eq sentinel)
          // 2. Second run did NOT throw — the stale captured failure was
          //    cleared at the start of runOnOp.
          assert(secondThrew == null)
          // 3. Second run returned a failed LogicalResult — the native pass
          //    manager observed signalFailure.
          assert(secondResult != null)
          assert(secondResult.failed)
          // 4. The run callback fired twice; the second invocation matched
          //    the signalFailure-only branch.
          assert(runCount.get() == 2)
        finally module.destroy()
      finally context.destroy()
    finally outerArena.close()

  val tests: Tests = Tests:
    test(
      "createExternalPass survives caller arena exit; pm.runOnOp dispatches run upcall; pm.destroy closes callback arena and fires destruct"
    ):
      runRealLifetimeCheck()
    test(
      "pm.runOnOp throws PassRunFailure wrapping the original throwable + non-null operation/pass segments when the run upcall throws"
    ):
      runCaptureAndSurfaceCheck()
    test(
      "pm.runOnOp clears stale captured failure between runs on the same PassManager"
    ):
      runStaleFailureClearedCheck()
