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
import org.llvm.mlir.scalalib.capi.ir.{Context, Module as MlirModule}

import java.lang.foreign.Arena

/** Everything the harness needs to elaborate: the DUT's width, the solved stimulus baked in as constants, and the
  * coverpoints to declare.
  *
  * The stimulus travels *inside* the parameter so that a single generator object covers every solved sequence —
  * `Generator.moduleName` already hashes the parameter, so distinct sequences get distinct module names and never
  * collide.
  */
final case class HarnessParameter(
  width:       Int,
  stimulus:    SolvedStimulus,
  coverpoints: Seq[Coverpoint])
    extends Parameter

object HarnessParameter:
  given upickle.default.ReadWriter[HarnessParameter] = upickle.default.macroRW

class HarnessLayers(parameter: HarnessParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** The harness's own IO: a clock domain and a `done` flag that rises once the solved sequence has been played out. The
  * generated SystemVerilog top watches `done` to end the simulation.
  */
class HarnessIO(parameter: HarnessParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val done  = Aligned(Bool())

class HarnessProbe(parameter: HarnessParameter) extends DVBundle[HarnessParameter, HarnessLayers](parameter)

/** The known coverpoint vocabulary for the [[Fifo]] DUT.
  *
  * A test names coverpoints by string; this map is what gives those names meaning. An unknown name fails elaboration
  * rather than silently never being hit, which would otherwise look like a coverage hole in a passing run.
  */
object FifoCoverpoints:
  val known: Seq[String] = Seq(
    "cover_enq_fire",
    "cover_deq_fire",
    "cover_full",
    "cover_empty",
    "cover_full_enq",
    "cover_empty_deq",
    "cover_simultaneous"
  )

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

  /** Make the harness end the simulation itself, via the sim dialect. */
  override def instrument(
    parameter: HarnessParameter,
    module:    MlirModule
  )(
    using Arena,
    Context
  ): Boolean =
    SimInstrument.terminateOnDone(module, moduleName(parameter))

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

    parameter.coverpoints.foreach { point =>
      val condition = point.name match
        case "cover_enq_fire"     => enqFire
        case "cover_deq_fire"     => deqFire
        case "cover_full"         => dut.io.full
        case "cover_empty"        => dut.io.empty
        case "cover_full_enq"     => dut.io.full & dut.io.enq.valid
        case "cover_empty_deq"    => dut.io.empty & dut.io.deq.ready
        case "cover_simultaneous" => enqFire & deqFire
        case other                =>
          throw new IllegalArgumentException(
            s"unknown coverpoint `$other`; FifoHarness understands ${FifoCoverpoints.known.mkString(", ")}"
          )
      Cover(condition.S, point.name)
    }

/** Emission of the runnable artifacts around an elaborated [[FifoHarness]]. */
object Harness:

  /** A clock/reset top that instantiates the harness and ends the simulation.
    *
    * The `$finish` lives here rather than inside the harness because sim-dialect instrumentation (injecting
    * `sim.clocked_terminate` into the lowered `hw.module`) is not wired up yet — see the plan's scope note. When it is,
    * the harness terminates itself and the `done` watcher below goes away; the timeout guard stays either way so a
    * harness that never raises `done` cannot hang CI.
    */
  def topString(parameter: HarnessParameter): String =
    val harnessModule = FifoHarness.moduleName(parameter)
    val timeout       = parameter.stimulus.cycles * 10 + 100
    s"""|// Generated by me.jiuyang.utlib.Harness — do not edit.
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

  /** Write `harness.sv` and `top.sv` into `outDir`; returns both paths. */
  def emit(parameter: HarnessParameter, outDir: os.Path): (os.Path, os.Path) =
    os.makeDir.all(outDir)
    val harness = outDir / "harness.sv"
    val top     = outDir / "top.sv"
    os.write.over(harness, FifoHarness.verilogString(parameter))
    os.write.over(top, topString(parameter))
    (harness, top)
