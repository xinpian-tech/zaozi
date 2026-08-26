// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class DefaultQueueTop %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/config.json --width 8 --entries 4 --pipe true --flow true --asyncReset true --resetMem true
// RUN: cd %t.dir && %{test} design %t.dir/config.json
// RUN: firld %t.dir/*.mlirbc --base-circuit DefaultQueueTop_width8_entries4_pipetrue_flowtrue_asyncResettrue_resetMemtrue --no-mangle | firtool --format=mlir | FileCheck %s
// RUN: rm -rf %t.dir

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
  def layers = Seq.empty

class DefaultQueueSpecIO(parameter: DefaultQueueSpecParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val enq   = Flipped(Decoupled(Bits(parameter.width)))
  val deq   = Aligned(Decoupled(Bits(parameter.width)))

class DefaultQueueSpecProbe(parameter: DefaultQueueSpecParameter)
    extends DVBundle[DefaultQueueSpecParameter, DefaultQueueSpecLayers](parameter)

// CHECK-LABEL: module DefaultQueueTop_width8_entries4_pipetrue_flowtrue_asyncResettrue_resetMemtrue(
// CHECK: SyncQueue_width8_depth4_almostEmptyLevel1_almostFullLevel1_stickyErrorfalse_enableDiagnosticsfalse_asyncResettrue_resetMemtrue queue_0 (
// CHECK: .resetN (~reset),
// CHECK: .diagnosticN (1'h1),
// CHECK: .dataIn (enq_bits)
// CHECK: module Ram_dataWidth8_depth4_asyncResettrue_resetMemtrue(
// CHECK: module SyncQueue_width8_depth4_almostEmptyLevel1_almostFullLevel1_stickyErrorfalse_enableDiagnosticsfalse_asyncResettrue_resetMemtrue(

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
