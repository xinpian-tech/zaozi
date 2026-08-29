// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVBundle, HWBundle, LayerInterface, Parameter, VerilogParameter, VerilogWrapper}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.valuetpe.{Clock, Reset}
import upickle.default.ReadWriter

/** The clock and reset origin — the board's oscillator and the tester's reset release. RTL cannot create a clock out of
  * nothing, so this is an external Verilog module declared through zaozi's `VerilogWrapper` and linked as an extmodule;
  * [[clockGenModel]] is the behavioral definition a simulation compiles alongside the emitted Verilog.
  *
  * It is not part of any chip, so it is never a module of the design: a test harness instantiates it and publishes the
  * clock onto the graph.
  */

case class ClockGenP(freqHz: Int) extends Parameter derives ReadWriter:
  require(freqHz > 0, s"clock frequency $freqHz must be positive")

class ClockGenPLayers(p: ClockGenP) extends LayerInterface(p):
  def layers = Seq.empty
class ClockGenPProbe(p: ClockGenP)  extends DVBundle[ClockGenP, ClockGenPLayers](p)
class ClockGenIO(p: ClockGenP)      extends HWBundle(p):
  val clock = Aligned(Clock())
  val reset = Aligned(Reset())

case class ClockGenVerilogP(FREQ_HZ: Int) extends VerilogParameter

@zaoziGenerator
object ClockGen extends VerilogWrapper[ClockGenP, ClockGenPLayers, ClockGenIO, ClockGenPProbe, ClockGenVerilogP]:
  def verilogModuleName(p: ClockGenP) = "ClockGen"
  def verilogParameter(p:  ClockGenP) = ClockGenVerilogP(p.freqHz)

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
