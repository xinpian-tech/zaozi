// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Probes, Sem, Txn, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class HavenSpiParameter() extends Parameter

given upickle.default.ReadWriter[HavenSpiParameter] = upickle.default.macroRW

class HavenSpiLayers(parameter: HavenSpiParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** The HAVEN simple_spi benchmark (OpenCores SPI master with a Wishbone-lite register face). */
class HavenSpiIO(parameter: HavenSpiParameter) extends HWBundle(parameter):
  val clk_i  = Flipped(Clock())
  val rst_i  = Flipped(Bool()) // active LOW
  val cyc_i  = Flipped(Bool())
  val stb_i  = Flipped(Bool())
  val adr_i  = Flipped(Bits(2))
  val we_i   = Flipped(Bool())
  val dat_i  = Flipped(Bits(8))
  val miso_i = Flipped(Bool())
  val dat_o  = Aligned(Bits(8))
  val ack_o  = Aligned(Bool())
  val inta_o = Aligned(Bool())
  val sck_o  = Aligned(Bool())
  val mosi_o = Aligned(Bool())

class HavenSpiProbe(parameter: HavenSpiParameter) extends DVBundle[HavenSpiParameter, HavenSpiLayers](parameter)

case class HavenSpiVerilogParams() extends VerilogParameter

@generator
object HavenSpi
    extends VerilogWrapper[HavenSpiParameter, HavenSpiLayers, HavenSpiIO, HavenSpiProbe, HavenSpiVerilogParams]:
  def verilogModuleName(parameter: HavenSpiParameter) = "simple_spi_top"
  def verilogParameter(parameter:  HavenSpiParameter) = HavenSpiVerilogParams()

  // Parameterless wrapper: the plain name keeps the extmodule decls identical for firld (see ExtAccum).
  override def moduleName(parameter: HavenSpiParameter): String = verilogModuleName(parameter)

class HavenSpiUTLayers(parameter: HavenSpiParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** One 16-bit drive word (DPI-legal width) packs the whole Wishbone request: A[0]=cyc, A[1]=stb, A[3:2]=adr, A[4]=we,
  * A[12:5]=dat, A[13]=miso, A[15:14] unused.
  */
class HavenSpiUTIO(parameter: HavenSpiParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val A     = Flipped(Bits(16))
  val DAT   = Aligned(Bits(8))
  val ACK   = Aligned(Bool())

class HavenSpiUTProbe(parameter: HavenSpiParameter) extends DVBundle[HavenSpiParameter, HavenSpiUTLayers](parameter):
  val DAT = ProbeRead(Bits(8), layers("Verification"))
  val ACK = ProbeRead(Bits(1), layers("Verification"))

/** The bus-protocol formal-CRV example: C = "a Wishbone write of 0xA5 to the data register completes" — the solver must
  * discover the handshake (hold cyc∧stb∧we with the address and data until the registered ack rises). The IP resets
  * *itself* through [[Txn.firstCycle]], so its true reset values (spcr = 0x10) hold in model and replay alike.
  */
@generator
object HavenSpiUT
    extends Generator[HavenSpiParameter, HavenSpiUTLayers, HavenSpiUTIO, HavenSpiUTProbe]
    with UT[HavenSpiParameter, HavenSpiUTIO]:
  override def moduleName(p: HavenSpiParameter): String = "HavenSpiUT"

  def architecture(parameter: HavenSpiParameter) =
    val io       = summon[Interface[HavenSpiUTIO]]
    val instance = HavenSpi.instantiate(parameter)

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    val first = Txn.firstCycle()

    instance.io.clk_i  := io.clock
    instance.io.rst_i  := !(io.reset.asBool | first) // active low: held low through cycle 0
    instance.io.cyc_i  := io.A.bit(0)
    instance.io.stb_i  := io.A.bit(1)
    instance.io.adr_i  := io.A.bits(3, 2)
    instance.io.we_i   := io.A.bit(4)
    instance.io.dat_i  := io.A.bits(12, 5)
    instance.io.miso_i := io.A.bit(13)
    io.DAT             := instance.io.dat_o
    io.ACK             := instance.io.ack_o

    Txn.assumeResetLow(io.reset)

    // C as typed value ∧ state semantics: the held write request (value — the driven Wishbone fields) meets the
    // design's acknowledgement (state — the registered ack). A *completed* write means the request is still held
    // on the cycle ack rises, i.e. the FIFO-push condition.
    Generate(
      Sem.value(
        io.A.bit(0) & io.A.bit(1) & io.A.bit(4) &
          (io.A.bits(3, 2) === 2.U(2).asBits) & (io.A.bits(12, 5) === 165.U(8).asBits)
      ) && Sem.state(instance.io.ack_o),
      "gen_spi_write_a5"
    )

    val probe = summon[ProbeInterface[HavenSpiUTProbe]]
    layer("Verification"):
      Probes.expose(probe.DAT, Bits(8), instance.io.dat_o)
      Probes.expose(probe.ACK, Bits(1), instance.io.ack_o.asBits)
