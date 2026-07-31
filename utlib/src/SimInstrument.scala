// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import org.llvm.circt.scalalib.capi.dialect.sim.{given_DialectApi as given_SimDialect, DialectApi as SimDialectApi}
import org.llvm.circt.scalalib.dialect.sim.operation.{
  given_ClockedTerminateApi,
  given_FormatConcatApi,
  given_FormatDecApi,
  given_FormatLiteralApi,
  given_PrintFormattedApi,
  given_PrintFormattedProcApi,
  given_TriggeredApi,
  ClockedTerminateApi,
  FormatConcatApi,
  FormatDecApi,
  FormatLiteralApi,
  PrintFormattedApi,
  PrintFormattedProcApi,
  TriggeredApi
}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi,
  given_BlockApi,
  given_IdentifierApi,
  given_LocationApi,
  given_ModuleApi,
  given_NamedAttributeApi,
  given_OperationApi,
  given_RegionApi,
  given_TypeApi,
  given_ValueApi,
  Block,
  Context,
  LocationApi,
  Module as MlirModule,
  NamedAttributeApi,
  Operation,
  OperationApi,
  Value,
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

  /** Inject a per-cycle transaction trace: a `sim.print` over the DUT instance's ports.
    *
    * This is the second use of the sim dialect in the framework, and the one the `fmt` op family exists for. It prints
    * one line per clock edge, outside reset, naming each port and its value — the log an engineer reads first when a
    * coverpoint is unexpectedly missed and the solved stimulus alone does not explain why.
    *
    * The labels are supplied by the caller rather than read out of the IR. CIRCT 1.147 keeps `hw.instance` port names
    * inside the referenced module's `!hw.modty` type rather than in `argNames`/`resultNames` attributes, so deriving
    * them here would mean decoding that type. The harness already knows its DUT's port order, so it passes them in;
    * reading them from the module type is a generalization the multi-DUT work needs anyway.
    *
    * A label of `""` skips that port — used for the clock, which is not an integer and cannot be formatted.
    *
    * @param operandLabels
    *   labels for the instance's operands, in order
    * @param resultLabels
    *   labels for the instance's results, in order
    */
  def traceOnClock(
    module:        MlirModule,
    instanceName:  String,
    operandLabels: Seq[String],
    resultLabels:  Seq[String],
    clockArgIndex: Int = 0,
    resetArgIndex: Int = 1
  )(
    using arena:   Arena,
    context:       Context
  ): Boolean =
    summon[SimDialectApi].loadDialect

    var injected = false
    module.getOperation.walk(
      { op =>
        if !injected && op.getName.str == "hw.instance"
          && op.getAttributeByName("instanceName").stringAttrGetValue == instanceName
        then
          val body   = op.getBlock
          val clock  = body.getArgument(clockArgIndex)
          val reset  = body.getArgument(resetArgIndex)
          val output = body.getTerminator
          val loc    = summon[LocationApi].locationUnknownGet
          val i1     = 1.integerTypeGet

          // Every op is inserted directly before `hw.output`, in creation
          // order, so the terminator stays last and each value dominates its
          // users.
          def emit(operation: Operation): Operation =
            body.insertOwnedOperationBefore(output, operation)
            operation

          val labelled =
            operandLabels.zipWithIndex.collect { case (l, i) if l.nonEmpty => l -> op.getOperand(i) } ++
              resultLabels.zipWithIndex.collect { case (l, i) if l.nonEmpty => l -> op.getResult(i) }

          // Guard: `!reset`, as comb.xor(reset, true).
          val one      = emit(
            summon[OperationApi].operationCreate(
              name = "hw.constant",
              location = loc,
              namedAttributes = Seq(
                summon[NamedAttributeApi].namedAttributeGet("value".identifierGet, 1.integerAttrGet(i1))
              ),
              resultsTypes = Some(Seq(i1))
            )
          ).getResult(0)
          val notReset = emit(
            summon[OperationApi].operationCreate(
              name = "comb.xor",
              location = loc,
              operands = Seq(reset, one),
              resultsTypes = Some(Seq(i1))
            )
          ).getResult(0)

          // The formatting and the print must go in *procedural* form —
          // `sim.triggered` holding `sim.proc.print`. `lower-sim-to-sv` marks
          // the Sim dialect illegal and only carries patterns for procedural
          // ops, and the `sim-proceduralize` transform that would convert a
          // bare `sim.print` is not exposed through CIRCT's C API. (This is
          // also why `sim.fmt.literal` fails to legalize outside a region.)
          val triggered = summon[TriggeredApi].op(clock, Some(notReset), loc)
          emit(triggered.operation)

          // Region body. The fmt ops reference values from the enclosing
          // block, which is legal: sim.triggered is not IsolatedFromAbove.
          given Block = triggered.block
          def bodyLiteral(text: String): Value =
            val o = summon[FormatLiteralApi].op(text, loc)
            o.operation.appendToBlock()
            o.result
          def bodyDecimal(value: Value): Value =
            val o = summon[FormatDecApi].op(value, false, loc)
            o.operation.appendToBlock()
            o.result

          val pieces = bodyLiteral("[txn]") +: labelled.flatMap { case (label, value) =>
            Seq(bodyLiteral(s" $label="), bodyDecimal(value))
          } :+ bodyLiteral("\n")
          val concat = summon[FormatConcatApi].op(pieces, loc)
          concat.operation.appendToBlock()
          val print  = summon[PrintFormattedProcApi].op(concat.result, None, loc)
          print.operation.appendToBlock()
          injected = true
        if injected then WalkResultEnum.Interrupt else WalkResultEnum.Advance
      },
      WalkEnum.PreOrder
    )
    injected
