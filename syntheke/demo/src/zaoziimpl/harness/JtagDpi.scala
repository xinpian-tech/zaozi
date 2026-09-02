// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl.harness

import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.zaozi.{DVBundle, HWBundle, LayerInterface, Parameter, VerilogParameter, VerilogWrapper}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bool, Clock, Reset}
import upickle.default.ReadWriter

/** The debug adapter as a wire out of the simulation: `JtagDpi` drives the TAP pins from bits a real debugger sends
  * over a socket, instead of from a script baked into the RTL. `sim/JtagDpi.sv` is its behavioral definition and
  * `sim/jtag_dpi.cc` the DPI-C behind it — together they are the adapter's hardware, the way a probe is hardware.
  *
  * The debugger is probe-rs (`syntheke/demo/simprobe`, beside this module): it walks the TAP, speaks DMI, drives the
  * debug module and knows the RISC-V debug spec. Nothing here knows any of that; it clocks bits and reports `tdo`.
  */

case class JtagDpiP(port: Int, tckDiv: Int) extends Parameter derives ReadWriter:
  require(port > 0 && port < 65536, s"port $port must be a TCP port")
  require(tckDiv >= 2, s"tck divisor $tckDiv must be at least 2")

class JtagDpiPLayers(p: JtagDpiP) extends LayerInterface(p):
  def layers = Seq.empty
class JtagDpiPProbe(p: JtagDpiP)  extends DVBundle[JtagDpiP, JtagDpiPLayers](p)
class JtagDpiIO(p: JtagDpiP)      extends HWBundle(p):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val tck   = Aligned(Clock())
  val tms   = Aligned(Bool())
  val tdi   = Aligned(Bool())
  val trstN = Aligned(Bool())
  val tdo   = Flipped(Bool())

case class JtagDpiVerilogP(PORT: Int, TCK_DIV: Int) extends VerilogParameter

@generator
object JtagDpi extends VerilogWrapper[JtagDpiP, JtagDpiPLayers, JtagDpiIO, JtagDpiPProbe, JtagDpiVerilogP]:
  def verilogModuleName(p: JtagDpiP) = "JtagDpi"
  def verilogParameter(p:  JtagDpiP) = JtagDpiVerilogP(p.port, p.tckDiv)
