// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Probes, Sem, Txn, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class HavenI2cParameter() extends Parameter

given upickle.default.ReadWriter[HavenI2cParameter] = upickle.default.macroRW

class HavenI2cLayers(parameter: HavenI2cParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** The OpenCores I2C master's Wishbone face, as `i2c_master_top` declares it. */
class HavenI2cIO(parameter: HavenI2cParameter) extends HWBundle(parameter):
  val wb_clk_i  = Flipped(Clock())
  val wb_rst_i  = Flipped(Bool())
  val arst_i    = Flipped(Bool())
  val wb_adr_i  = Flipped(Bits(3))
  val wb_dat_i  = Flipped(Bits(8))
  val wb_we_i   = Flipped(Bool())
  val wb_stb_i  = Flipped(Bool())
  val wb_cyc_i  = Flipped(Bool())
  val scl_pad_i = Flipped(Bool())
  val sda_pad_i = Flipped(Bool())
  val wb_dat_o     = Aligned(Bits(8))
  val wb_ack_o     = Aligned(Bool())
  val wb_inta_o    = Aligned(Bool())
  val scl_pad_o    = Aligned(Bool())
  val scl_padoen_o = Aligned(Bool())
  val sda_pad_o    = Aligned(Bool())
  val sda_padoen_o = Aligned(Bool())

class HavenI2cProbe(parameter: HavenI2cParameter) extends DVBundle[HavenI2cParameter, HavenI2cLayers](parameter)

case class HavenI2cVerilogParams() extends VerilogParameter

@generator
object HavenI2c
    extends VerilogWrapper[HavenI2cParameter, HavenI2cLayers, HavenI2cIO, HavenI2cProbe, HavenI2cVerilogParams]:
  def verilogModuleName(parameter: HavenI2cParameter) = "i2c_master_top"
  def verilogParameter(parameter: HavenI2cParameter)  = HavenI2cVerilogParams()
  override def moduleName(parameter: HavenI2cParameter): String = verilogModuleName(parameter)

/** `ctrlValue` is the byte an intent wants written to the control register (address 2): bit 7 enables the core,
  * bit 6 enables interrupts.
  */
case class HavenI2cCtrlParameter(ctrlValue: Int) extends Parameter

given upickle.default.ReadWriter[HavenI2cCtrlParameter] = upickle.default.macroRW

class HavenI2cCtrlLayers(parameter: HavenI2cCtrlParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** Drive ports carry the same names and widths as HAVEN's `i2c_master_top_seq_item` fields, so a witness renders
  * straight into its sequence. `stb`/`cyc` are held asserted here — the replay driver performs the full handshake
  * per transaction, so what the intent describes is the *sequence of register accesses*.
  */
class HavenI2cUTIO(parameter: HavenI2cCtrlParameter) extends HWBundle(parameter):
  val clock     = Flipped(Clock())
  val reset     = Flipped(Reset())
  val wb_adr_i  = Flipped(Bits(3))
  val wb_dat_i  = Flipped(Bits(8))
  val wb_we_i   = Flipped(Bool())
  val arst_i    = Flipped(Bool())
  // The I2C bus lines are solver-driven too: a witness may hold SCL low to stretch the clock, or pull SDA to
  // acknowledge. HAVEN needs a hand-written slave BFM to produce that behaviour; here it is part of the solution.
  val scl_pad_i = Flipped(Bool())
  val sda_pad_i = Flipped(Bool())
  val ACK       = Aligned(Bool())
  val DAT       = Aligned(Bits(8))

class HavenI2cUTProbe(parameter: HavenI2cCtrlParameter)
    extends DVBundle[HavenI2cCtrlParameter, HavenI2cCtrlLayers](parameter):
  val ACK = ProbeRead(Bits(1), layers("Verification"))

/** First i2c intent, deliberately minimal: a write of `ctrlValue` to the control register. Its purpose is to answer
  * the scaling question — whether circt-bmc solves at all through a three-module I2C hierarchy — before anything
  * more ambitious is attempted.
  */
@generator
object HavenI2cCtrlUT
    extends Generator[HavenI2cCtrlParameter, HavenI2cCtrlLayers, HavenI2cUTIO, HavenI2cUTProbe]
    with UT[HavenI2cCtrlParameter, HavenI2cUTIO]:
  override def moduleName(p: HavenI2cCtrlParameter): String = s"HavenI2cCtrlUT_${p.ctrlValue}"

  def architecture(parameter: HavenI2cCtrlParameter) =
    val io       = summon[Interface[HavenI2cUTIO]]
    val instance = HavenI2c.instantiate(HavenI2cParameter())
    instance.io.wb_clk_i  := io.clock
    instance.io.wb_rst_i  := io.reset.asBool
    instance.io.arst_i    := io.arst_i
    instance.io.wb_adr_i  := io.wb_adr_i
    instance.io.wb_dat_i  := io.wb_dat_i
    instance.io.wb_we_i   := io.wb_we_i
    instance.io.wb_stb_i  := true.B
    instance.io.wb_cyc_i  := true.B
    instance.io.scl_pad_i := io.scl_pad_i
    instance.io.sda_pad_i := io.sda_pad_i
    io.ACK                := instance.io.wb_ack_o
    io.DAT                := instance.io.wb_dat_o

    Txn.assumeResetLow(io.reset)

    Generate(
      Sem.value(
        io.wb_we_i & (io.wb_adr_i === 2.U(3).asBits) &
          (io.wb_dat_i === parameter.ctrlValue.U(8).asBits)
      ),
      s"gen_ctrl_write_${parameter.ctrlValue}"
    )

    val probe = summon[ProbeInterface[HavenI2cUTProbe]]
    layer("Verification"):
      Probes.expose(probe.ACK, Bits(1), instance.io.wb_ack_o.asBits)

/** Targets what HAVEN's generated `seq_item` forbids itself from reaching.
  *
  * Its transaction carries `constraint c_reset { arst_i == 1'b0; }`, pinning the asynchronous reset, so no sequence
  * it generates can ever exercise the `if (~nReset)` branches — 
  * they are unreachable by construction of its own constraint, not by anything about the design. Here `arst_i` is
  * simply an input the intent may name.
  */
@generator
object HavenI2cResetUT
    extends Generator[HavenI2cCtrlParameter, HavenI2cCtrlLayers, HavenI2cUTIO, HavenI2cUTProbe]
    with UT[HavenI2cCtrlParameter, HavenI2cUTIO]:
  override def moduleName(p: HavenI2cCtrlParameter): String = s"HavenI2cResetUT_${p.ctrlValue}"

  def architecture(parameter: HavenI2cCtrlParameter) =
    val io       = summon[Interface[HavenI2cUTIO]]
    val instance = HavenI2c.instantiate(HavenI2cParameter())
    instance.io.wb_clk_i  := io.clock
    instance.io.wb_rst_i  := io.reset.asBool
    instance.io.arst_i    := io.arst_i
    instance.io.wb_adr_i  := io.wb_adr_i
    instance.io.wb_dat_i  := io.wb_dat_i
    instance.io.wb_we_i   := io.wb_we_i
    instance.io.wb_stb_i  := true.B
    instance.io.wb_cyc_i  := true.B
    instance.io.scl_pad_i := io.scl_pad_i
    instance.io.sda_pad_i := io.sda_pad_i
    io.ACK                := instance.io.wb_ack_o
    io.DAT                := instance.io.wb_dat_o

    Txn.assumeResetLow(io.reset)

    given ClockEvent = posedge(io.clock)
    // Partial temporal specification: assert the asynchronous reset, then within one to three cycles perform a
    // control-register write. What happens in between is the solver's to choose.
    Generate(
      Sem.temporal(io.arst_i.S.##(1, Some(3))((io.wb_we_i & (io.wb_adr_i === 2.U(3).asBits)).S)),
      "gen_async_reset_then_write"
    )

    val probe = summon[ProbeInterface[HavenI2cUTProbe]]
    layer("Verification"):
      Probes.expose(probe.ACK, Bits(1), instance.io.wb_ack_o.asBits)
