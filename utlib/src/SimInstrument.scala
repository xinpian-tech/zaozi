// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import org.llvm.circt.scalalib.capi.dialect.sim.{given_DialectApi as given_SimDialect, DialectApi as SimDialectApi}
import org.llvm.circt.scalalib.dialect.sim.operation.{given_ClockedTerminateApi, ClockedTerminateApi}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi,
  given_BlockApi,
  given_IdentifierApi,
  given_LocationApi,
  given_ModuleApi,
  given_OperationApi,
  given_RegionApi,
  Context,
  LocationApi,
  Module as MlirModule,
  Operation,
  WalkEnum,
  WalkResultEnum
}

import java.lang.foreign.Arena

/** Injects `sim` dialect ops into an already-lowered `hw` module.
  *
  * This is where the framework's two dialects meet: the `smt` dialect produced the stimulus, and the `sim` dialect
  * makes the resulting testbench a self-contained simulation program. It runs between `lowFIRRTLToHW` and `hwToSV`, the
  * only point where the design is `hw`-level (so `sim` ops are legal) but not yet SystemVerilog.
  *
  * The harness at that point is an `hw.module` whose clock is block argument 0 and whose `done` signal is an operand of
  * the terminating `hw.output`. Inserting `sim.clocked_terminate(clock, done, success, quiet)` there makes the
  * generated testbench call `$finish` by itself.
  *
  * Note on ordering: `lower-sim-to-sv` marks the Sim dialect illegal and only carries patterns for *procedural* ops,
  * and the `sim-proceduralize` transform that would lower a non-procedural op into that form is not exposed through
  * CIRCT's C API. `sim.clocked_terminate` is `NonProceduralOp` — so the op inserted here is lowered by firtool's own
  * HW-to-SV pipeline, which runs the full sequence internally, not by a bare `lower-sim-to-sv` pass.
  */
object SimInstrument:

  /** Append a `sim.clocked_terminate` to the `hw.module` named `harnessModuleName`, firing when the `hw.output` operand
    * at `doneResultIndex` is high on the module's clock (block argument `clockArgIndex`).
    *
    * Does nothing if the module is not found, so an emitter that runs this on a design without a harness is a no-op
    * rather than a crash.
    */
  def terminateOnDone(
    module:            MlirModule,
    harnessModuleName: String,
    clockArgIndex:     Int = 0,
    doneResultIndex:   Int = 0
  )(
    using arena:       Arena,
    context:           Context
  ): Boolean =
    summon[SimDialectApi].loadDialect

    // The insertion happens *inside* the walk callback. The `Operation` handle
    // a walk yields is scoped to that callback: letting it escape and using it
    // afterwards throws a ScopedAccessError from the Panama layer, because the
    // segment's lifetime has already ended.
    var injected = false
    module.getOperation.walk(
      { op =>
        if !injected && op.getName.str == "hw.module"
          && op.getAttributeByName("sym_name").stringAttrGetValue == harnessModuleName
        then
          val body   = op.getFirstRegion.getFirstBlock
          val clock  = body.getArgument(clockArgIndex)
          val output = body.getTerminator
          val done   = output.getOperand(doneResultIndex)

          val terminate = summon[ClockedTerminateApi].op(
            clock = clock,
            condition = done,
            success = true,
            verbose = false,
            location = summon[LocationApi].locationUnknownGet
          )
          // `hw.output` must stay last, so insert before it rather than append.
          body.insertOwnedOperationBefore(output, terminate.operation)
          injected = true
        if injected then WalkResultEnum.Interrupt else WalkResultEnum.Advance
      },
      WalkEnum.PreOrder
    )
    injected
