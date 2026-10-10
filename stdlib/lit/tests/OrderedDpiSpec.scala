// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class OrderedDpiTestBench %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: cd %t.dir && %{test} design %S/../../ut/src/sync_queue/parameter.json
// RUN: circt-opt %t.dir/OrderedDpiTestBench.hw.mlirbc | FileCheck %s --check-prefix=IR
// RUN: FileCheck %s --check-prefix=SV --input-file=%t.dir/OrderedDpiTestBench.sv
// RUN: FileCheck %s --check-prefix=DPI --input-file=%t.dir/OrderedDpiTestBench.json
// RUN: rm -rf %t.dir

// IR-LABEL: hw.module @OrderedDpiTestBench()
// IR: hw.instance "clock" @Clock_periodNs10
// IR: hw.instance "dut" @SyncQueue_
// IR: sv.always posedge
// IR-NEXT: sv.func.call.procedural @begin_cycle()
// IR-NEXT: %[[STEP:.*]]:7 = sv.func.call.procedural @step()
// IR-NEXT: sv.func.call.procedural @consume(%[[STEP]]#4, %[[STEP]]#6)
// IR-NEXT: sv.func.call.procedural @end_cycle()
// IR: sv.passign %{{.*}}, %[[STEP]]#4 : i8

// SV: import "DPI-C" context function void begin_cycle
// SV: import "DPI-C" context function void consume
// SV-LABEL: module OrderedDpiTestBench();
// SV-NOT: always #
// SV-DAG: Clock_periodNs10 clock (
// SV-DAG: wire [[FALLING:[A-Za-z_][A-Za-z_0-9]*]] = ~{{[A-Za-z_][A-Za-z_0-9]*}};
// SV-NOT: always #
// SV: always @(posedge [[FALLING]]) begin
// SV-NEXT: begin_cycle();
// SV-NEXT: [[STATUS:[A-Za-z_][A-Za-z_0-9]*]] = ordered_step({{.*}});
// SV-NEXT: consume({{.*}}, [[STATUS]]);
// SV-NEXT: end_cycle();
// SV: dataIn_stimulus <=
// SV-NOT: {{^ *}}always @
// SV-NOT: always #
// SV: endmodule

// DPI-DAG: "function": "begin_cycle"
// DPI-DAG: "function": "ordered_step"
// DPI-DAG: "function": "consume"
// DPI-DAG: "function": "end_cycle"

import me.jiuyang.stdlib.queue.default.{SyncQueueLayers, SyncQueueParameter, SyncQueueProbe, given}
import me.jiuyang.stdlib.ut.{SyncQueueTestBench, SyncQueueTestBenchIO}
import me.jiuyang.tblib.*
import me.jiuyang.tblib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import org.llvm.circt.scalalib.capi.dialect.sim.DPIDirection

@generator
object OrderedDpiTestBench
    extends TestbenchGenerator[SyncQueueParameter, SyncQueueLayers, SyncQueueTestBenchIO, SyncQueueProbe]:
  val dut = SyncQueueTestBench.dut

  override def moduleName(parameter: SyncQueueParameter): String = "OrderedDpiTestBench"
  def clockPeriodNs(parameter:       SyncQueueParameter): Long   = 10

  def simulation(parameter: SyncQueueParameter) =
    val tb      = summon[Testbench[SyncQueueTestBenchIO]]
    val begin   = tb.dpiFunction("begin_cycle", None, Seq.empty)
    val step    = tb.dpiFunction(
      "step",
      Some("ordered_step"),
      Seq(
        DpiArg("resetN", DPIDirection.Out, 1),
        DpiArg("pushRequestN", DPIDirection.Out, 1),
        DpiArg("popRequestN", DPIDirection.Out, 1),
        DpiArg("diagnosticN", DPIDirection.Out, 1),
        DpiArg("dataIn", DPIDirection.Out, parameter.width),
        DpiArg("done", DPIDirection.Out, 1),
        DpiArg("status", DPIDirection.Return, 32, signed = true)
      )
    )
    val consume = tb.dpiFunction(
      "consume",
      None,
      Seq(DpiArg("data", DPIDirection.In, parameter.width), DpiArg("status", DPIDirection.In, 32, signed = true))
    )
    val end     = tb.dpiFunction("end_cycle", None, Seq.empty)
    tb.onClock(tb.fallingClock):
      tb.dpiCallProcedural(begin)
      val values = tb.dpiCallProcedural(step)
      tb.dpiCallProcedural(consume, Seq(values("dataIn"), values("status")))
      tb.dpiCallProcedural(end)
      tb.io.resetN       := values("resetN")
      tb.io.pushRequestN := values("pushRequestN")
      tb.io.popRequestN  := values("popRequestN")
      tb.io.diagnosticN  := values("diagnosticN")
      tb.io.dataIn       := values("dataIn")
