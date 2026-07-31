// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.ClockEvent
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.dialect.firrtl.operation.{given_ModuleApi, Circuit}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module as MlirModule}

import java.lang.foreign.Arena

/** Everything the harness needs to elaborate: the DUT's width, the solved stimulus baked in as constants, and the
  * coverpoints to declare.
  *
  * The stimulus travels *inside* the parameter so that a single generator object covers every solved sequence —
  * `Generator.moduleName` already hashes the parameter, so distinct sequences get distinct module names and never
  * collide.
  */
final case class HarnessParameter(
  width:    Int,
  stimulus: SolvedStimulus,
  txnTrace: Boolean = false)
    extends Parameter

object HarnessParameter:
  given upickle.default.ReadWriter[HarnessParameter] = upickle.default.macroRW

class HarnessLayers(parameter: HarnessParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** The harness's own IO: a clock domain and a `done` flag that rises once the solved sequence has been played out. The
  * generated SystemVerilog top watches `done` to end the simulation.
  */
class HarnessIO(parameter: HarnessParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val done  = Aligned(Bool())

class HarnessProbe(parameter: HarnessParameter) extends DVBundle[HarnessParameter, HarnessLayers](parameter)

/** A testbench around [[Fifo]] driven entirely by a solved stimulus.
  *
  * Each cycle of the sequence becomes a `when (cycle === N)` block that drives the DUT's Decoupled ports with the
  * values the solver chose. Coverpoints become clocked SVA `Cover` properties, which reach SystemVerilog as `cover
  * property` statements and are counted by Verilator.
  */
@generator
object FifoHarness extends Generator[HarnessParameter, HarnessLayers, HarnessIO, HarnessProbe] with HasSvEmit:
  override def appendSubmodules(
    parameter: HarnessParameter
  )(
    using Arena,
    Context,
    Circuit
  ): Unit = Fifo.module(FifoParameter(parameter.width)).appendToCircuit()

  /** The [[Fifo]] instance's port order, as the DUT's IO declares it. Passed to the trace injector because CIRCT keeps
    * instance port names in the module type rather than in an attribute.
    */
  // The clock is skipped (not an integer); so is reset, which the trace
  // already filters on and would print as a constant 0 on every line.
  private val dutOperandLabels = Seq("", "", "enq_valid", "enq_bits", "deq_ready")
  private val dutResultLabels  = Seq("enq_ready", "deq_valid", "deq_bits", "empty", "full")

  /** Sim-dialect instrumentation: always self-terminate, and optionally emit a per-cycle transaction trace. */
  override def instrument(
    parameter: HarnessParameter,
    module:    MlirModule
  )(
    using Arena,
    Context
  ): Boolean =
    val terminated = SimInstrument.terminateOnDone(module, moduleName(parameter))
    val traced     =
      if !parameter.txnTrace then false
      else SimInstrument.traceOnClock(module, "dut", dutOperandLabels, dutResultLabels)
    terminated || traced

  def architecture(parameter: HarnessParameter) =
    val io = summon[Interface[HarnessIO]]

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
    given ClockEvent = posedge(io.clock)

    // `instance`, not `instantiate`: the latter also dumps the sub-module to
    // a separate .mlirbc for external `firld` linking, which this in-process
    // emitter does not use. The module itself is appended by
    // `appendSubmodules` below.
    val dut = Fifo.instance(FifoParameter(parameter.width))
    dut.io.clock := io.clock
    dut.io.reset := io.reset

    // Cycle tracker. A one-hot shift register rather than a counter: FIRRTL's
    // `add` grows the result width, so `count := count + 1.U` would be a
    // width-mismatched connect. One register per cycle keeps every connect
    // width-exact, and the sequences a unit test plays are short.
    val cycles = parameter.stimulus.cycles
    val stages = (0 to cycles).map(i => RegInit(if i == 0 then true.B else false.B))
    (cycles to 1 by -1).foreach(i => stages(i) := stages(i - 1))
    stages(0) := false.B

    // `done` rises the cycle after the last stimulus slot has been driven.
    io.done := stages(cycles)

    // Defaults: no traffic unless a solved slot says otherwise.
    dut.io.enq.valid := false.B
    dut.io.enq.bits  := 0.U(parameter.width)
    dut.io.deq.ready := false.B

    parameter.stimulus.txns.foreach { txn =>
      txn.kind match
        case TxnKind.Enqueue =>
          when(stages(txn.cycle)) {
            dut.io.enq.valid := true.B
            dut.io.enq.bits  := txn.payload.toInt.U(parameter.width)
          }
        case TxnKind.Dequeue =>
          when(stages(txn.cycle)) {
            dut.io.deq.ready := true.B
          }
        case TxnKind.Idle    => ()
    }

    val enqFire = dut.io.enq.valid & dut.io.enq.ready
    val deqFire = dut.io.deq.valid & dut.io.deq.ready

    // Coverpoints are bound to real signals, not to names. `name` survives
    // only because it becomes the SystemVerilog cover label and is therefore
    // the key Verilator reports back in coverage.dat — that string is
    // irreducible; the *condition* need not be one.
    //
    // The ClockEvent is derived at each use site rather than once at the top:
    // `posedge` materializes a reference op in the current region, and reusing
    // one from an enclosing block inside a `layer` region fails FIRRTL's
    // dominance check.
    // `Block` is taken at the *call site*, not captured here: a helper that
    // closes over the enclosing block would append its ops there, and a
    // condition defined inside a `layer` region would then fail to dominate
    // its own use.
    def cover[T <: Referable[Bool] & HasOperation](
      name:      String,
      condition: T
    )(
      using Arena,
      Context,
      Block,
      InstanceContext
    ): Unit =
      given ClockEvent = posedge(io.clock)
      Cover(condition.S, name)

    // Black-box view: the DUT's own ports.
    cover("cover_enq_fire", enqFire)
    cover("cover_deq_fire", deqFire)
    cover("cover_full", dut.io.full)
    cover("cover_empty", dut.io.empty)
    cover("cover_full_enq", dut.io.full & dut.io.enq.valid)
    cover("cover_empty_deq", dut.io.empty & dut.io.deq.ready)
    cover("cover_simultaneous", enqFire & deqFire)

    // White-box view: the DUT's internal state, read through its probe. These
    // are signals no port exposes, which is the whole point — and they are
    // typed references, so a typo is a compile error rather than a coverpoint
    // that silently never fires.
    layer("Verification"):
      val bothSlots = Wire(Bool())
      bothSlots <== dut.probe.isFull
      val headOnly  = Wire(Bool())
      headOnly <== dut.probe.valid0
      val tailUsed  = Wire(Bool())
      tailUsed <== dut.probe.valid1
      val accepted  = Wire(Bool())
      accepted <== dut.probe.enqFire
      val released  = Wire(Bool())
      released <== dut.probe.deqFire

      cover("cover_probe_both_slots", bothSlots)
      cover("cover_probe_head_only", headOnly & !tailUsed)
      cover("cover_probe_accepted", accepted)
      cover("cover_probe_released", released)
      cover("cover_probe_pass_through", accepted & released)

/** Emission of the runnable artifacts around an elaborated [[FifoHarness]]. */
object Harness:

  /** A clock/reset top that instantiates the harness.
    *
    * It deliberately contains no `$finish` of its own: the harness ends the simulation itself through the
    * `sim.clocked_terminate` that [[SimInstrument]] injects. The only control flow here is the timeout guard, so a
    * harness that never raises `done` — a real failure mode when a DUT deadlocks — fails loudly instead of hanging CI.
    *
    * When `trace` is set the top also opens a VCD via `$dumpfile`/`$dumpvars`. That needs Verilator's `--trace`, which
    * [[VerilatorRunner]] passes in the same mode.
    */
  def topString(parameter: HarnessParameter, trace: Boolean = false): String =
    val harnessModule = FifoHarness.moduleName(parameter)
    val timeout       = parameter.stimulus.cycles * 10 + 100
    // FIRRTL layers are opt-in: firtool emits each layer's `bind` into its own
    // `layers-<module>-<layer>.sv`, and nothing pulls it in. The testbench
    // enables a layer by including that file — which is what makes the DUT's
    // white-box probes, and any coverpoint bound to them, actually exist.
    val layerIncludes = s"""|  `include "layers-$harnessModule-Verification.sv"
                            |""".stripMargin
    val dumpBlock     =
      if !trace then ""
      else s"""|
               |  initial begin
               |    $$dumpfile("${Harness.traceFileName}");
               |    $$dumpvars(0, top);
               |  end
               |""".stripMargin
    s"""|// Generated by me.jiuyang.utlib.Harness — do not edit.
        |$layerIncludes
        |module top;
        |  logic clock = 1'b0;
        |  logic reset = 1'b1;
        |  wire  done;
        |
        |  always #5 clock = ~clock;
        |
        |  $harnessModule harness (
        |    .clock (clock),
        |    .reset (reset),
        |    .done  (done)
        |  );
        |
        |$dumpBlock
        |  initial begin
        |    repeat (4) @(posedge clock);
        |    reset = 1'b0;
        |  end
        |
        |  // `done` is observed only for the log line; the harness's own
        |  // sim.clocked_terminate is what calls $$finish.
        |  always @(posedge clock) begin
        |    if (!reset && done) $$display("HARNESS-DONE");
        |  end
        |
        |  initial begin
        |    repeat ($timeout) @(posedge clock);
        |    $$display("HARNESS-TIMEOUT after $timeout cycles");
        |    $$fatal;
        |  end
        |endmodule
        |""".stripMargin

  /** The VCD file the generated top writes, relative to the run directory. */
  val traceFileName: String = "trace.vcd"

  /** firtool's marker for a new output file inside a single `exportVerilog` stream. */
  private val fileMarker = raw"""^// -+ 8< -+ FILE "([^"]+)" -+ 8< -+$$""".r

  /** Split firtool's emitted Verilog into the files it asked for.
    *
    * A design that uses layers does not lower to one file: firtool emits the layer bind modules as separate files and
    * has the main file `` `include `` them. In a single-`exportVerilog` stream those files arrive inline, separated by
    * `// ----- 8< ----- FILE "name" ----- 8< -----` markers. Writing the stream verbatim to one file leaves the
    * `` `include `` dangling and Verilator fails with "Cannot find include file".
    *
    * @return
    *   the main file's content, then (name, content) for each embedded file
    */
  def splitEmittedVerilog(verilog: String): (String, Seq[(String, String)]) =
    val main  = StringBuilder()
    val files = scala.collection.mutable.ListBuffer.empty[(String, StringBuilder)]
    verilog.linesIterator.foreach {
      case fileMarker(name) => files += (name -> StringBuilder())
      case line             =>
        val sink = files.lastOption.map(_._2).getOrElse(main)
        sink ++= line
        sink ++= "\n"
    }
    (main.toString, files.toSeq.map((name, content) => name -> content.toString))

  /** Write the harness, its layer bind files, and the top into `outDir`; returns every SystemVerilog path to compile.
    */
  def emit(parameter: HarnessParameter, outDir: os.Path, trace: Boolean = false): Seq[os.Path] =
    os.makeDir.all(outDir)
    val (mainSv, extraFiles) = splitEmittedVerilog(FifoHarness.verilogString(parameter))
    val harness              = outDir / "harness.sv"
    val top                  = outDir / "top.sv"
    os.write.over(harness, mainSv)
    os.write.over(top, topString(parameter, trace))
    val extras               = extraFiles.map { (name, content) =>
      val path = outDir / name
      os.write.over(path, content)
      path
    }
    // Only the main file and the top are compiled directly; the rest are
    // pulled in by the `include` firtool put in the main file, so they just
    // need to exist next to it.
    Seq(harness, top)
