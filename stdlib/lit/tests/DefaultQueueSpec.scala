// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class DefaultQueueTop %s --
// RUN: rm -rf %t.depth4.dir %t.depth1.dir && mkdir -p %t.depth4.dir %t.depth1.dir
// RUN: %{test} config %t.depth4.dir/config.json --width 8 --entries 4 --pipe true --flow true --asyncReset true --resetMem true
// RUN: cd %t.depth4.dir && %{test} design %t.depth4.dir/config.json
// RUN: firld %t.depth4.dir/*.mlirbc --base-circuit DefaultQueueTop_width8_entries4_pipetrue_flowtrue_asyncResettrue_resetMemtrue --no-mangle | firtool --format=mlir > %t.depth4.dir/out.sv
// RUN: FileCheck %s --check-prefix=DEPTH4 --input-file=%t.depth4.dir/out.sv
// RUN: %{test} config %t.depth1.dir/config.json --width 8 --entries 1 --pipe true --flow true --asyncReset true --resetMem true
// RUN: cd %t.depth1.dir && %{test} design %t.depth1.dir/config.json
// RUN: firld %t.depth1.dir/*.mlirbc --base-circuit DefaultQueueTop_width8_entries1_pipetrue_flowtrue_asyncResettrue_resetMemtrue --no-mangle | firtool --format=mlir > %t.depth1.dir/out.sv
// RUN: FileCheck %s --check-prefix=DEPTH1 --input-file=%t.depth1.dir/out.sv
// RUN: not grep -E 'SyncQueue_width|Ram_dataWidth' %t.depth1.dir/out.sv
// RUN: rm -rf %t.depth4.dir %t.depth1.dir

import java.lang.foreign.Arena

import mainargs.TokensReader
import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.stdlib.queue.*
import me.jiuyang.stdlib.queue.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module as MlirModule}

case class DefaultQueueSpecParameter(
  width:      Int,
  entries:    Int,
  pipe:       Boolean,
  flow:       Boolean,
  asyncReset: Boolean,
  resetMem:   Boolean)
    extends Parameter

given upickle.default.ReadWriter[DefaultQueueSpecParameter] = upickle.default.macroRW

class DefaultQueueSpecLayers(parameter: DefaultQueueSpecParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class DefaultQueueSpecIO(parameter: DefaultQueueSpecParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val enq   = Flipped(Decoupled(Bits(parameter.width)))
  val deq   = Aligned(Decoupled(Bits(parameter.width)))

class DefaultQueueSpecProbe(parameter: DefaultQueueSpecParameter)
    extends DVBundle[DefaultQueueSpecParameter, DefaultQueueSpecLayers](parameter)

// DEPTH4-LABEL: module DefaultQueueTop_width8_entries4_pipetrue_flowtrue_asyncResettrue_resetMemtrue(
// DEPTH4: SyncQueue_width8_depth4_almostEmptyLevel1_almostFullLevel1_stickyErrorfalse_enableDiagnosticsfalse_asyncResettrue_resetMemtrue queue_0 (
// DEPTH4: .resetN (~reset),
// DEPTH4: .diagnosticN (1'h1),
// DEPTH4: .dataIn (enq_bits)
// DEPTH4: module Ram_dataWidth8_depth4_asyncResettrue_resetMemtrue(
// DEPTH4: module SyncQueue_width8_depth4_almostEmptyLevel1_almostFullLevel1_stickyErrorfalse_enableDiagnosticsfalse_asyncResettrue_resetMemtrue(

// DEPTH1-LABEL: module DefaultQueueTop_width8_entries1_pipetrue_flowtrue_asyncResettrue_resetMemtrue(
// DEPTH1: SingleEntryQueue_width8_pipetrue_flowtrue_asyncResettrue_resetMemtrue queue (
// DEPTH1: .clock (clock),
// DEPTH1: .reset (reset),
// DEPTH1: .enq_ready (enq_ready),
// DEPTH1: .enq_valid (enq_valid),
// DEPTH1: .enq_bits (enq_bits),
// DEPTH1: .deq_ready (deq_ready),
// DEPTH1: .deq_valid (deq_valid),
// DEPTH1: .deq_bits (deq_bits)
// DEPTH1-LABEL: module SingleEntryQueue_width8_pipetrue_flowtrue_asyncResettrue_resetMemtrue_Verification();
// DEPTH1: single_entry_queue_enqueue_accept:
// DEPTH1: single_entry_queue_dequeue_accept:
// DEPTH1: single_entry_queue_enqueue_dequeue_same_cycle:
// DEPTH1: single_entry_queue_empty_to_full:
// DEPTH1: single_entry_queue_full_to_empty:
// DEPTH1: single_entry_queue_flow_bypass:
// DEPTH1: single_entry_queue_pipe_replace:
// DEPTH1-LABEL: module SingleEntryQueue_width8_pipetrue_flowtrue_asyncResettrue_resetMemtrue(
// DEPTH1: reg{{ +}}[[FULL:[_A-Za-z0-9]+]];
// DEPTH1: reg{{ +}}[7:0] [[DATA:[_A-Za-z0-9]+]];
// DEPTH1: wire{{ +}}[[EMPTY:[_A-Za-z0-9]+]] = ~[[FULL]];
// DEPTH1: wire{{ +}}[[ENQ_READY:[_A-Za-z0-9]+]] = [[EMPTY]] | deq_ready;
// DEPTH1: wire{{ +}}[[DEQ_VALID:[_A-Za-z0-9]+]] = [[FULL]] | enq_valid;
// DEPTH1: always @(posedge clock or posedge reset) begin
// DEPTH1: if (reset) begin
// DEPTH1: [[FULL]] <= 1'h0;
// DEPTH1: [[DATA]] <= 8'h0;
// DEPTH1: assign enq_ready = [[ENQ_READY]];
// DEPTH1: assign deq_valid = [[DEQ_VALID]];
// DEPTH1: assign deq_bits = [[FULL]] ? [[DATA]] : enq_bits;
// DEPTH1: bind SingleEntryQueue_width8_pipetrue_flowtrue_asyncResettrue_resetMemtrue SingleEntryQueue_width8_pipetrue_flowtrue_asyncResettrue_resetMemtrue_Verification verification ();

@generator
object DefaultQueueTop
    extends Generator[
      DefaultQueueSpecParameter,
      DefaultQueueSpecLayers,
      DefaultQueueSpecIO,
      DefaultQueueSpecProbe
    ]:
  override def moduleName(p: DefaultQueueSpecParameter): String =
    s"DefaultQueueTop_width${p.width}_entries${p.entries}_pipe${p.pipe}_flow${p.flow}" +
      s"_asyncReset${p.asyncReset}_resetMem${p.resetMem}"

  def architecture(parameter: DefaultQueueSpecParameter) =
    val io = summon[Interface[DefaultQueueSpecIO]]
    val queue = Queue(
      QueueParameter(
        gen = io.enq.bits.getType,
        entries = parameter.entries,
        pipe = parameter.pipe,
        flow = parameter.flow,
        asyncReset = parameter.asyncReset,
        resetMem = parameter.resetMem
      )
    )

    queue.clock     := io.clock
    queue.reset     := io.reset
    queue.enq.bits  := io.enq.bits
    queue.enq.valid := io.enq.valid
    io.enq.ready    := queue.enq.ready
    io.deq.bits     := queue.deq.bits
    io.deq.valid    := queue.deq.valid
    queue.deq.ready := io.deq.ready
