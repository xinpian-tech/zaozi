// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{
  DVRecord,
  Generator,
  HWBundle,
  HWRecord,
  LayerInterface,
  Parameter,
  VerilogParameter,
  VerilogWrapper
}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.{Clock, Record, Reset}
import upickle.default.ReadWriter

/** The design's clock and reset origin: one clock/reset pair per named tap, every tap fanned out from one [[ClockGen]].
  * RTL cannot create a clock out of nothing, so ClockGen is an external Verilog module (zaozi `VerilogWrapper`) — the
  * design links its declaration, and [[clockGenModel]] is the behavioral definition a simulation compiles alongside the
  * emitted Verilog.
  */

case class ClockSourceP(freqHz: Int, taps: Vector[String]) extends Parameter derives ReadWriter:
  require(freqHz > 0, s"clock frequency $freqHz must be positive")
  require(taps.nonEmpty, "a clock source needs at least one tap")

class ClockSourcePLayers(p: ClockSourceP) extends LayerInterface(p):
  def layers = Seq.empty
class ClockSourcePProbe(p: ClockSourceP)  extends DVRecord[ClockSourceP, ClockSourcePLayers](p)
class ClockSourcePIO(p: ClockSourceP)     extends HWRecord(p):
  val outs = p.taps.map(n => Aligned(n, new ClockRecord))

/** What [[ClockGen]] drives: the one pair every tap fans out. */
class ClockGenIO(p: ClockSourceP) extends HWBundle(p):
  val clock = Aligned(Clock())
  val reset = Aligned(Reset())

case class ClockGenVerilogP(FREQ_HZ: Int) extends VerilogParameter

@zaoziGenerator
object ClockGen
    extends VerilogWrapper[ClockSourceP, ClockSourcePLayers, ClockGenIO, ClockSourcePProbe, ClockGenVerilogP]:
  def verilogModuleName(p: ClockSourceP) = "ClockGen"
  def verilogParameter(p:  ClockSourceP) = ClockGenVerilogP(p.freqHz)

/** The behavioral definition of [[ClockGen]], simulation only: the clock at `FREQ_HZ` on a 1ns timescale, reset held
  * through the first 20 edges, and a watchdog ending a simulation that never finishes on its own.
  */
val clockGenModel: String = """`timescale 1ns / 1ps
module ClockGen #(
    parameter FREQ_HZ = 100000000
) (
    output reg clock,
    output reg reset
);
  initial begin
    clock = 1'b0;
    reset = 1'b1;
    repeat (20) @(posedge clock);
    reset = 1'b0;
  end
  always #(500000000 / FREQ_HZ) clock = ~clock;
  initial begin
    #10ms;
    $display("[ClockGen] watchdog: simulation did not finish");
    $finish;
  end
endmodule
"""

@zaoziGenerator
object ClockSourceGen extends Generator[ClockSourceP, ClockSourcePLayers, ClockSourcePIO, ClockSourcePProbe]:
  def architecture(p: ClockSourceP) =
    val io  = summon[Interface[ClockSourcePIO]]
    val gen = ClockGen.instantiate(p)
    p.taps.foreach { n =>
      io.field[Record](n).field[Clock]("clock") := gen.io.clock
      io.field[Record](n).field[Reset]("reset") := gen.io.reset
    }
