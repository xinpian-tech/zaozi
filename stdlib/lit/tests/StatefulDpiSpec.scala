// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class StatefulDpiLit %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: cd %t.dir && %{test} %S/../../ut/src/sync_queue/parameter.json
// RUN: circt-opt %t.dir/stateful.mlirbc | FileCheck %s --check-prefix=IR
// RUN: FileCheck %s --check-prefix=SV --input-file=%t.dir/stateful.sv

// IR-LABEL: hw.module @StatefulDpiTestBenchWrapper()
// IR: hw.instance "clockGenerator" @Clock_periodNs10
// IR: sv.initial
// IR: sim.proc.dpi.call @open(
// IR: sv.if
// IR: sim.terminate failure
// IR: sim.triggered
// IR: sv.case
// IR: sim.proc.dpi.call @request()
// IR: sv.if
// IR: sv.passign
// IR: sim.proc.dpi.call @consume(
// IR: sim.proc.dpi.call @poll()
// IR: sv.if
// IR: sim.proc.dpi.call @complete(
// IR: sim.proc.dpi.call @tick()
// IR: hw.instance "testbench" @StatefulDpiTestBench
// IR: sv.assign

// SV: import "DPI-C" context function int model_open(
// SV: input string
// SV-LABEL: module StatefulDpiTestBenchWrapper();
// SV-NOT: always #
// SV: Clock_periodNs10 clockGenerator (
// SV-NOT: always #
// SV: initial begin
// SV: model_open("dram.yaml",
// SV: always @(posedge
// SV: case (state)
// SV: model_request()
// SV: model_consume(
// SV: model_poll(
// SV: model_complete(
// SV: model_tick();
// SV: StatefulDpiTestBench testbench (
// SV-NOT: always #
// SV: endmodule

import me.jiuyang.stdlib.queue.default.{SyncQueue, SyncQueueLayers, SyncQueueParameter, SyncQueueProbe, given}
import me.jiuyang.tblib.*
import me.jiuyang.tblib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FIRRTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.ltl.{DialectApi as LTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.verif.{DialectApi as VerifDialectApi, given}
import org.llvm.circt.scalalib.dialect.sim.operation.DPIDirection
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, TypeApi, Value, given}
import java.lang.foreign.Arena

class StatefulDpiIO(parameter: SyncQueueParameter) extends HWBundle(parameter):
  val clock        = Flipped(Clock())
  val resetN       = Flipped(Reset())
  val pushRequestN = Flipped(Bool())
  val popRequestN  = Flipped(Bool())
  val diagnosticN  = Flipped(Bool())
  val dataIn       = Flipped(UInt(parameter.width))
  val empty        = Aligned(Bool())
  val dataOut      = Aligned(UInt(parameter.width))

@generator
object StatefulDpiTestBench
    extends TestbenchGenerator[SyncQueueParameter, SyncQueueLayers, StatefulDpiIO, SyncQueueProbe]:
  override def moduleName(parameter: SyncQueueParameter): String = "StatefulDpiTestBench"
  def clockPeriodNs(parameter:       SyncQueueParameter): Long   = 10

  def architecture(parameter: SyncQueueParameter) =
    val io  = summon[Interface[StatefulDpiIO]]
    val dut = SyncQueue.instantiate(parameter)
    dut.io.clock        := io.clock
    dut.io.resetN       := io.resetN
    dut.io.pushRequestN := io.pushRequestN
    dut.io.popRequestN  := io.popRequestN
    dut.io.diagnosticN  := io.diagnosticN
    dut.io.dataIn       := io.dataIn
    io.empty            := dut.io.empty
    io.dataOut          := dut.io.dataOut

  def simulation(parameter: SyncQueueParameter) =
    val tb       = summon[Testbench[StatefulDpiIO]]
    val sv       = summon[SVApi]
    val hw       = summon[HWApi]
    val comb     = summon[CombApi]
    def c(
      value: BigInt,
      width: Int
    )(
      using Block
    ): Value = hw.constant(value, width)
    val open     = tb.dpiFunction(
      "open",
      Some("model_open"),
      Seq(
        DpiArg("config", DPIDirection.In, DpiType.String),
        DpiArg("base", DPIDirection.In, 64),
        DpiArg("status", DPIDirection.Return, 32, signed = true)
      )
    )
    val request  =
      tb.dpiFunction("request", Some("model_request"), Seq(DpiArg("status", DPIDirection.Return, 32, signed = true)))
    val poll     = tb.dpiFunction(
      "poll",
      Some("model_poll"),
      Seq(
        DpiArg("d0", DPIDirection.Out, 32),
        DpiArg("d1", DPIDirection.Out, 32),
        DpiArg("d2", DPIDirection.Out, 32),
        DpiArg("d3", DPIDirection.Out, 32),
        DpiArg("status", DPIDirection.Return, 32)
      )
    )
    val consume  =
      tb.dpiFunction("consume", Some("model_consume"), Seq(DpiArg("data", DPIDirection.In, parameter.width)))
    val complete = tb.dpiFunction("complete", Some("model_complete"), Seq(DpiArg("data", DPIDirection.In, 128)))
    val tick     = tb.dpiFunction("tick", Some("model_tick"), Seq.empty)
    val state    = sv.reg(3.integerTypeGet, "state")
    val cycle    = sv.reg(8.integerTypeGet, "cycle")
    val reset    = sv.reg(1.integerTypeGet, "reset_n")
    val push     = sv.reg(1.integerTypeGet, "push_n")
    val pop      = sv.reg(1.integerTypeGet, "pop_n")
    val data     = sv.reg(parameter.width.integerTypeGet, "data")
    val result   = sv.reg(128.integerTypeGet, "result")
    // Bind each DUT input once; branch-local assignments update the explicit registers.
    tb.io.resetN       := reset.readInOut
    tb.io.pushRequestN := push.readInOut
    tb.io.popRequestN  := pop.readInOut
    tb.io.diagnosticN  := c(1, 1)
    tb.io.dataIn       := data.readInOut
    assert(scala.util.Try(tb.io.resetN.value).isFailure)
    assert(scala.util.Try(tb.io.empty := c(0, 1)).isFailure)
    assert(scala.util.Try(tb.io.dataIn := c(0, parameter.width)).isFailure)

    tb.initial:
      sv.blockingAssign(state, c(0, 3))
      sv.blockingAssign(cycle, c(0, 8))
      sv.blockingAssign(reset, c(0, 1))
      sv.blockingAssign(push, c(1, 1))
      sv.blockingAssign(pop, c(1, 1))
      sv.blockingAssign(data, c(0, parameter.width))
      sv.blockingAssign(result, c(0, 128))
      val status =
        tb.dpiCallProcedural(open, Seq(sv.stringConstant("dram.yaml"), c(BigInt("fedcba9876543210", 16), 64)))("status")
      tb.finish(status.slt(c(0, 32)), success = false)

    tb.onClock(tb.clock):
      sv.nonBlockingAssign(cycle, cycle.readInOut + c(1, 8))
      sv.switch(
        state.readInOut,
        Seq(
          SVCase(
            0, {
              sv.nonBlockingAssign(reset, c(1, 1))
              sv.nonBlockingAssign(state, c(1, 3))
            }
          ),
          SVCase(
            1, {
              val taken = tb.dpiCallProcedural(request)("status")
              sv.ifElse(taken =/= c(0, 32)) {
                sv.nonBlockingAssign(data, c(0x5a, parameter.width))
                sv.nonBlockingAssign(push, c(0, 1))
                sv.nonBlockingAssign(state, c(2, 3))
              }()
            }
          ),
          SVCase(
            2, {
              sv.nonBlockingAssign(push, c(1, 1))
              sv.ifElse(tb.io.empty.value === c(0, 1)) {
                tb.dpiCallProcedural(consume, Seq(tb.io.dataOut.value))
                sv.nonBlockingAssign(pop, c(0, 1))
                sv.nonBlockingAssign(state, c(3, 3))
              }()
            }
          ),
          SVCase(
            3, {
              sv.nonBlockingAssign(pop, c(1, 1))
              val values = tb.dpiCallProcedural(poll)
              sv.ifElse(values("status") =/= c(0, 32)) {
                sv.nonBlockingAssign(result, comb.concat(Seq(values("d3"), values("d2"), values("d1"), values("d0"))))
                sv.nonBlockingAssign(state, c(4, 3))
              }()
            }
          ),
          SVCase(
            4, {
              tb.dpiCallProcedural(complete, Seq(result.readInOut))
            }
          )
        )
      ) {
        tb.finish(c(1, 1), success = false)
      }
      tb.dpiCallProcedural(tick)
      tb.finish(state.readInOut === c(4, 3))
      tb.finish(cycle.readInOut === c(40, 8), success = false)

object StatefulDpiLit:
  def main(args: Array[String]): Unit =
    val parameter = upickle.default.read[SyncQueueParameter](os.read(os.Path(args(0), os.pwd)))
    val arena     = Arena.ofConfined()
    given Arena   = arena
    given Context = summon[ContextApi].contextCreate
    try
      summon[FIRRTLDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      StatefulDpiTestBench.dumpMlirbc(parameter)
      val modules = os.list(os.pwd).filter(_.ext == "mlirbc").map(os.read.bytes)
      val module  = StatefulDpiTestBench.module(parameter, modules)
      try
        val schema   = module.toDpiJson
        val config   = schema("dpi_functions").arr.find(_("function").str == "model_open").get("arguments")(0)
        assert(config("type").str == "string")
        assert(!config.obj.contains("width"))
        val bytecode = module.toMlirBytecode
        os.write.over(os.pwd / "stateful.sv", module.toVerilog)
        assert(module.toMlirBytecode.sameElements(bytecode))
        os.write.over(os.pwd / "stateful.mlirbc", bytecode)
        os.write.over(os.pwd / "stateful.json", ujson.write(schema, indent = 2))
      finally module.destroy()
    finally
      summon[Context].destroy()
      arena.close()
