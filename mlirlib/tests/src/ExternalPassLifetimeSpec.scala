// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.mlirlib.tests

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.pass.{*, given}
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.util.concurrent.atomic.AtomicInteger
import utest.*

object ExternalPassLifetimeSpec extends TestSuite:

  private def runRealLifetimeCheck(): Unit =
    val outerArena = Zone.open()
    try
      given Zone  = outerArena
      val context = summon[ContextApi].contextCreate
      try
        given Context       = context
        context.allowUnregisteredDialects(true)
        val unknownLocation = summon[LocationApi].locationUnknownGet
        val module          = summon[ModuleApi].moduleCreateEmpty(unknownLocation)
        try
          val moduleOp = module.getOperation
          val pm       = summon[PassManagerApi].passManagerCreate

          val constructCounter = new AtomicInteger(0)
          val destructCounter  = new AtomicInteger(0)
          val runCounter       = new AtomicInteger(0)

          var callbackState: Ptr[Byte] = null

          try
            val transientArena = Zone.open()
            try
              given Zone    = transientArena
              val uniquePtr = alloc[Long]().asInstanceOf[Ptr[Byte]]
              val passId    = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirTypeIDCreate(uniquePtr)(out)))
              val pass      = summon[PassApi].createExternalPass(
                passId = passId,
                name = "TestExternalPass",
                argument = "test-external-pass",
                description = "external-pass lifetime fixture",
                opName = "",
                dependentDialects = Seq.empty,
                constructCallback = () => constructCounter.incrementAndGet(),
                destructCallback = () => destructCounter.incrementAndGet(),
                initializeCallback = None,
                cloneCallback = () => (),
                runCallback = (_: Operation, _: ExternalPass) => runCounter.incrementAndGet()
              )
              callbackState = pass._callbackState
              pm.addOwnedPass(pass)
            finally transientArena.close()

            assert(callbackState != null)
            assert(pm._callbackStates.contains(callbackState))
            assert(NativeCallbacks.entry[Any](callbackState) != null)

            val result = pm.runOnOp(moduleOp)
            assert(result.succeeded)
            assert(runCounter.get() >= 1)
          finally
            try pm.destroy()
            finally
              if callbackState != null then
                intercept[NoSuchElementException](NativeCallbacks.entry[Any](callbackState))
                assert(destructCounter.get() >= 1)
        finally module.destroy()
      finally context.destroy()
    finally outerArena.close()

  private class RunUpcallSentinel(msg: String) extends RuntimeException(msg)

  private def runCaptureAndSurfaceCheck(): Unit =
    val outerArena = Zone.open()
    try
      given Zone  = outerArena
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
            val transientArena = Zone.open()
            try
              given Zone    = transientArena
              val uniquePtr = alloc[Long]().asInstanceOf[Ptr[Byte]]
              val passId    = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirTypeIDCreate(uniquePtr)(out)))
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

          assert(!pmRunReturned)
          assert(thrown != null)
          val thrownFailure = thrown.failure
          assert(thrownFailure.cause eq sentinel)
          assert(thrownFailure.opSegment != null)
          assert(thrownFailure.passSegment != null)
          assert(thrown.getCause eq sentinel)
        finally module.destroy()
      finally context.destroy()
    finally outerArena.close()

  private def runStaleFailureClearedCheck(): Unit =
    val outerArena = Zone.open()
    try
      given Zone  = outerArena
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
            val transientArena = Zone.open()
            try
              given Zone    = transientArena
              val uniquePtr = alloc[Long]().asInstanceOf[Ptr[Byte]]
              val passId    = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirTypeIDCreate(uniquePtr)(out)))
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
                runCallback = (_: Operation, ep: ExternalPass) =>
                  runCount.incrementAndGet() match
                    case 1 => throw sentinel
                    case _ => ep.signalFailure()
              )
              capturedPass = pass
              pm.addOwnedPass(pass)
            finally transientArena.close()

            try pm.runOnOp(moduleOp)
            catch case e: PassRunFailure => firstThrown = e

            assert(capturedPass._lastFailure.get() != null)

            try secondResult = pm.runOnOp(moduleOp)
            catch case t: Throwable => secondThrew = t
          finally
            try pm.destroy()
            catch case _: Throwable => ()

          assert(firstThrown != null)
          assert(firstThrown.failure.cause eq sentinel)
          assert(secondThrew == null)
          assert(secondResult != null)
          assert(secondResult.failed)
          assert(runCount.get() == 2)
        finally module.destroy()
      finally context.destroy()
    finally outerArena.close()

  private def runOnWorkers(): Unit = org.llvm.mlir.scalalib.Mlir.withContext {
    val context  = summon[Context]
    context.enableMultithreading(true)
    val visited  = new java.util.concurrent.ConcurrentLinkedQueue[Operation]()
    val siblings = (0 until 32).map(i => s"module @child$i {}").mkString("\n")
    val module   = summon[ModuleApi].moduleCreateParse(s"module { $siblings }")
    try
      val manager     = summon[PassManagerApi].passManagerCreate
      val caller      = Thread.currentThread().getId()
      val clones      = new AtomicInteger()
      val runs        = new AtomicInteger()
      val constructed = new AtomicInteger()
      val destructed  = new AtomicInteger()
      val worker      = new java.util.concurrent.atomic.AtomicBoolean(false)
      try
        val id   = TypeID(
          nativeResult[MlirStructs.MlirTypeID](out =>
            mlirApi.mlirTypeIDCreate(alloc[Long]().asInstanceOf[Ptr[Byte]])(out)
          )
        )
        val pass = summon[PassApi].createExternalPass(
          id,
          "Worker",
          "worker",
          "visit nested modules",
          "builtin.module",
          Seq.empty,
          () => constructed.incrementAndGet(),
          () => destructed.incrementAndGet(),
          None,
          () => clones.incrementAndGet(),
          (op, _) => {
            visited.add(op)
            if Thread.currentThread().getId() != caller && worker.compareAndSet(false, true) then System.gc()
            runs.incrementAndGet()
          }
        )
        manager.getNestedUnder("builtin.module").addOwnedPass(pass)
        assert(manager.runOnOp(module.getOperation).succeeded)
      finally manager.destroy()
      import scala.jdk.CollectionConverters.*
      val names       = visited.iterator().asScala.map(_.getAttributeByName("sym_name").stringAttrGetValue).toSet
      assert(names == (0 until 32).map(i => s"child$i").toSet)
      assert(runs.get() == 32, constructed.get() == destructed.get())
      if Runtime.getRuntime.availableProcessors() > 1 then assert(worker.get(), clones.get() > 0)
    finally module.destroy()
  }

  val tests: Tests = Tests:
    test("worker threads enter Scala, allocate, clone and destruct nested passes")(runOnWorkers())
    test(
      "createExternalPass survives caller zone exit; pm.runOnOp dispatches run upcall; pm.destroy closes callback zone and fires destruct"
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
