// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Yuhang Zeng <unlsycn@unlsycn.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class DwbbFifoTop %s --
// RUN: %{test} config %t.json --width 16 --useAsyncReset false
// RUN: %{test} design %t.json
// RUN: firld *.mlirbc --base-circuit DwbbFifoTop_2a94835e --no-mangle | firtool --format=mlir | FileCheck %s
// RUN: rm %t.json *.mlirbc -f

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.dwbb.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module as MlirModule}

import java.lang.foreign.Arena
import mainargs.TokensReader

case class QueueSpecParameter(width: Int, useAsyncReset: Boolean) extends Parameter
given upickle.default.ReadWriter[QueueSpecParameter] = upickle.default.macroRW

class QueueSpecLayers(parameter: QueueSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

// CHECK-DAG:      module DwbbFifoTop_{{.*}}(
// CHECK-DAG-NEXT:   input         clock,
// CHECK-DAG-NEXT:                 reset,
// CHECK-DAG-NEXT:   output        i_ready,
// CHECK-DAG-NEXT:   input         i_valid,
// CHECK-DAG-NEXT:   input  [15:0] i_bits,
// CHECK-DAG-NEXT:   input         o_ready,
// CHECK-DAG-NEXT:   output        o_valid,
// CHECK-DAG-NEXT:   output [15:0] o_bits
// CHECK-DAG-NEXT: );
class QueueSpecIO(parameter: QueueSpecParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(
    if parameter.useAsyncReset then AsyncReset()
    else Reset()
  )
  val i     = Flipped(Decoupled(UInt(parameter.width)))
  val o     = Aligned(Decoupled(UInt(parameter.width)))

class QueueSpecProbe(parameter: QueueSpecParameter) extends DVBundle[QueueSpecParameter, QueueSpecLayers](parameter)

// CHECK-DAG:      DW_fifo_s1_sf #(
// CHECK-DAG-NEXT:   .af_level(1),
// CHECK-DAG-NEXT:   .rst_mode(3),
// CHECK-DAG-NEXT:   .depth(32),
// CHECK-DAG-NEXT:   .width(16),
// CHECK-DAG-NEXT:   .ae_level(1),
// CHECK-DAG-NEXT:   .err_mode(2)
// CHECK-DAG-NEXT: ) fifo (
// CHECK-DAG-NEXT:   .clk          (clock),
// CHECK-DAG-NEXT:   .rst_n        (~reset),
// CHECK-DAG-NEXT:   .push_req_n   (~(i_valid & ~_fifo_full)),
// CHECK-DAG-NEXT:   .pop_req_n    (~(o_ready & ~_fifo_empty)),
// CHECK-DAG-NEXT:   .diag_n       (1'h1),
// CHECK-DAG-NEXT:   .data_in      (i_bits),
// CHECK-DAG-NEXT:   .empty        (_fifo_empty),
// CHECK-DAG-NEXT:   .almost_empty (/* unused */),
// CHECK-DAG-NEXT:   .half_full    (/* unused */),
// CHECK-DAG-NEXT:   .almost_full  (/* unused */),
// CHECK-DAG-NEXT:   .full         (_fifo_full),
// CHECK-DAG-NEXT:   .error        (/* unused */),
// CHECK-DAG-NEXT:   .data_out     (o_bits)
// CHECK-DAG-NEXT: );
@generator
object DwbbFifoTop extends Generator[QueueSpecParameter, QueueSpecLayers, QueueSpecIO, QueueSpecProbe]:
  def architecture(parameter: QueueSpecParameter) =
    val io   = summon[Interface[QueueSpecIO]]
    val fifo = Queue(QueueParameter(io.i.bits.getType, 32, useAsyncReset = parameter.useAsyncReset))

    fifo.clock     := io.clock
    fifo.reset     := io.reset
    fifo.enq.bits  := io.i.bits
    fifo.enq.valid := io.i.valid
    io.i.ready     := fifo.enq.ready
    io.o.bits      := fifo.deq.bits
    io.o.valid     := fifo.deq.valid
    fifo.deq.ready := io.o.ready
