// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.{DVBundle, HWBundle, LayerInterface, Parameter, VerilogParameter, VerilogWrapper}
import me.jiuyang.zaozi.default.{*, given}
import upickle.default.ReadWriter

/** The memory at the far end of the chip's memory port: an AXI slave whose timing is Ramulator's and whose contents are
  * a byte store beside it. `sim/DramDpi.sv` is the behavioral definition — the AXI handshakes and the beat counting,
  * nothing else — and `sim/dram_dpi.cc` the DPI-C that hands each access to Ramulator and answers when it says so.
  *
  * DRAM is not something to write in RTL for a simulation. It is not on the die, it is not an IP of this design, and a
  * register file pretending to be one teaches a design nothing about the latency it will actually see. So the chip
  * publishes a memory port and the testbench terminates it in a real DRAM simulator.
  *
  * The device it models is `sim/dram.yaml`, Ramulator's own exported configuration, vendored beside the model; the
  * library is `nix build .#syntheke-ramulator`.
  */

case class DramDpiP(configFile: String, base: Long, periodPs: Long, shape: AxiShape) extends Parameter
    derives ReadWriter:
  require(configFile.nonEmpty, "the DRAM model needs a Ramulator configuration")
  require(base >= 0, s"base 0x${base.toHexString} must be non-negative")
  require(periodPs > 0, s"clock period $periodPs ps must be positive")
  require(shape.dataBits == 128, s"the DRAM port carries 128-bit beats, got ${shape.dataBits}")
  require(shape.addrBits <= 32, s"the DRAM port addresses at most a 32-bit space, got ${shape.addrBits}")
  require(base <= 0xffffffffL, s"base 0x${base.toHexString} must fit the 32-bit space the port addresses")

class DramDpiPLayers(p: DramDpiP) extends LayerInterface(p):
  def layers = Seq.empty
class DramDpiPProbe(p: DramDpiP)  extends DVBundle[DramDpiP, DramDpiPLayers](p)
// The port is the Record flavour of the AXI shape, the same one the harness's own boundary carries, so the two connect
// as whole aggregates instead of leaf by leaf.
class DramDpiIO(p: DramDpiP)      extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val in  = Flipped(new AxiPortRecord(p.shape))

case class DramDpiVerilogP(CONFIG: String, BASE: BigInt, PERIOD_PS: BigInt, ID_W: Int, ADDR_W: Int)
    extends VerilogParameter

@generator
object DramDpi extends VerilogWrapper[DramDpiP, DramDpiPLayers, DramDpiIO, DramDpiPProbe, DramDpiVerilogP]:
  def verilogModuleName(p: DramDpiP) = "DramDpi"
  def verilogParameter(p: DramDpiP)  =
    DramDpiVerilogP(p.configFile, BigInt(p.base), BigInt(p.periodPs), p.shape.idBits, p.shape.addrBits)
