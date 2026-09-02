// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl.harness

import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.zaozi.{DVBundle, HWBundle, LayerInterface, Parameter, VerilogParameter, VerilogWrapper}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.valuetpe.{Clock, Reset}
import upickle.default.ReadWriter

/** The clock and reset origin — the board's oscillator and the tester's reset release. RTL cannot create a clock out of
  * nothing, so this is an external Verilog module declared through zaozi's `VerilogWrapper` and linked as an extmodule;
  * `sim/ClockGen.sv` is the behavioral definition a simulation compiles alongside the emitted Verilog.
  *
  * It is not part of any chip, so it is never a module of the design: a test harness instantiates it and publishes the
  * clock onto the graph.
  */

case class ClockGenP(freqHz: Int, watchdogMs: Int) extends Parameter derives ReadWriter:
  require(freqHz > 0, s"clock frequency $freqHz must be positive")
  require(watchdogMs > 0, s"watchdog $watchdogMs ms must be positive")

class ClockGenPLayers(p: ClockGenP) extends LayerInterface(p):
  def layers = Seq.empty
class ClockGenPProbe(p: ClockGenP)  extends DVBundle[ClockGenP, ClockGenPLayers](p)
class ClockGenIO(p: ClockGenP)      extends HWBundle(p):
  val clock = Aligned(Clock())
  val reset = Aligned(Reset())

case class ClockGenVerilogP(FREQ_HZ: Int, WATCHDOG_MS: Int) extends VerilogParameter

@zaoziGenerator
object ClockGen extends VerilogWrapper[ClockGenP, ClockGenPLayers, ClockGenIO, ClockGenPProbe, ClockGenVerilogP]:
  def verilogModuleName(p: ClockGenP) = "ClockGen"
  def verilogParameter(p:  ClockGenP) = ClockGenVerilogP(p.freqHz, p.watchdogMs)
