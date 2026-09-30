// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class OrderedDpiLit %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: cd %t.dir && %{test} %S/../../ut/src/sync_queue/parameter.json
// RUN: circt-opt %t.dir/ordered.mlirbc | FileCheck %s --check-prefix=IR
// RUN: FileCheck %s --check-prefix=SV --input-file=%t.dir/ordered.sv
// RUN: rm -rf %t.dir

// IR-LABEL: hw.module @OrderedDpiTestBenchWrapper()
// IR: sim.triggered
// IR-NEXT: sim.proc.dpi.call @begin_cycle()
// IR-NEXT: %[[STEP:.*]]:7 = sim.proc.dpi.call @step()
// IR-NEXT: sim.proc.dpi.call @consume(%[[STEP]]#4, %[[STEP]]#6)
// IR-NEXT: sim.proc.dpi.call @end_cycle()
// IR: sv.passign %{{.*}}, %[[STEP]]#4 : i8
// IR: hw.instance "testbench" @OrderedDpiTestBench

// SV: import "DPI-C" context function void begin_cycle
// SV: import "DPI-C" context function void consume
// SV-LABEL: module OrderedDpiTestBenchWrapper();
// SV: wire [[FALLING:[A-Za-z_][A-Za-z_0-9]*]] = ~clock;
// SV-NEXT: always @(posedge [[FALLING]]) begin
// SV-NEXT: begin_cycle();
// SV-NEXT: [[STATUS:[A-Za-z_][A-Za-z_0-9]*]] = ordered_step({{.*}});
// SV-NEXT: consume({{.*}}, [[STATUS]]);
// SV-NEXT: end_cycle();
// SV: dataIn_stimulus <=
// SV-NOT: {{^ *}}always @
// SV: endmodule

import me.jiuyang.stdlib.queue.default.{SyncQueueLayers, SyncQueueParameter, SyncQueueProbe, given}
import me.jiuyang.stdlib.ut.{SyncQueueTestBench, SyncQueueTestBenchIO}
import me.jiuyang.tblib.*
import me.jiuyang.tblib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FIRRTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.ltl.{DialectApi as LTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.verif.{DialectApi as VerifDialectApi, given}
import org.llvm.circt.scalalib.dialect.sim.operation.DPIDirection
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, given}

import java.lang.foreign.Arena

@generator
object OrderedDpiTestBench
    extends TestbenchGenerator[SyncQueueParameter, SyncQueueLayers, SyncQueueTestBenchIO, SyncQueueProbe]:
  override def moduleName(parameter: SyncQueueParameter): String = "OrderedDpiTestBench"
  def clockPeriodNs(parameter:       SyncQueueParameter): Long   = 10

  def architecture(parameter: SyncQueueParameter) = SyncQueueTestBench.architecture(parameter)

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

object OrderedDpiLit:
  def main(args: Array[String]): Unit =
    val parameter = upickle.default.read[SyncQueueParameter](os.read(os.Path(args(0), os.pwd)))
    val arena     = Arena.ofConfined()
    given Arena   = arena
    given Context = summon[ContextApi].contextCreate
    try
      summon[FIRRTLDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      OrderedDpiTestBench.dumpMlirbc(parameter)
      val modules = os.list(os.pwd).filter(_.ext == "mlirbc").map(os.read.bytes)
      val module  = OrderedDpiTestBench.module(parameter, modules)
      try
        val bytecode = module.toMlirBytecode
        val schema   = module.toDpiJson
        assert(
          schema("dpi_functions").arr.map(_("function").str).toSet ==
            Set("begin_cycle", "ordered_step", "consume", "end_cycle")
        )
        os.write.over(os.pwd / "ordered.sv", module.toVerilog)
        assert(module.toMlirBytecode.sameElements(bytecode))
        assert(module.toDpiJson == schema)
        os.write.over(os.pwd / "ordered.mlirbc", bytecode)
      finally module.destroy()
    finally
      summon[Context].destroy()
      arena.close()
