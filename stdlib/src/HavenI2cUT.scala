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

/** Which residual class an intent targets. `nReset` in bit_ctrl is `arst_i` (ARST_LVL is 0, so the XOR is the
  * identity) and its reset branches are `if (~nReset)`, i.e. taken while `arst_i` is LOW.
  */
enum I2cTarget:
  /** The `if (~nReset)` branches: pull arst_i low, then release it and do a register write. */
  case ResetPulse

  /** The `slave_wait` branch of the clock-enable generator: a slave holding SCL low stretches the clock. Reaching
    * it needs bus behaviour, which is why HAVEN needs a slave BFM and a solver does not.
    */
  case ClockStretch

  /** Arbitration lost: SDA reads back low while the master is releasing it. */
  case ArbitrationLost

case class HavenI2cTargetParameter(targetOrdinal: Int) extends Parameter:
  def target: I2cTarget = I2cTarget.fromOrdinal(targetOrdinal)

object HavenI2cTargetParameter:
  def apply(t: I2cTarget): HavenI2cTargetParameter = HavenI2cTargetParameter(t.ordinal)

given upickle.default.ReadWriter[HavenI2cTargetParameter] = upickle.default.macroRW

class HavenI2cTargetLayers(parameter: HavenI2cTargetParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class HavenI2cTargetIO(parameter: HavenI2cTargetParameter) extends HWBundle(parameter):
  val clock     = Flipped(Clock())
  val reset     = Flipped(Reset())
  val wb_adr_i  = Flipped(Bits(3))
  val wb_dat_i  = Flipped(Bits(8))
  val wb_we_i   = Flipped(Bool())
  val arst_i    = Flipped(Bool())
  val scl_pad_i = Flipped(Bool())
  val sda_pad_i = Flipped(Bool())
  val ACK       = Aligned(Bool())
  val DAT       = Aligned(Bits(8))

class HavenI2cTargetProbe(parameter: HavenI2cTargetParameter)
    extends DVBundle[HavenI2cTargetParameter, HavenI2cTargetLayers](parameter):
  val ACK = ProbeRead(Bits(1), layers("Verification"))

/** One intent per residual class, each written as anchors with solver-chosen spacing. */
@generator
object HavenI2cTargetUT
    extends Generator[HavenI2cTargetParameter, HavenI2cTargetLayers, HavenI2cTargetIO, HavenI2cTargetProbe]
    with UT[HavenI2cTargetParameter, HavenI2cTargetIO]:
  override def moduleName(p: HavenI2cTargetParameter): String = s"HavenI2cTargetUT_${p.target}"

  def architecture(parameter: HavenI2cTargetParameter) =
    val io       = summon[Interface[HavenI2cTargetIO]]
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
    val ctrlWrite    = io.wb_we_i & (io.wb_adr_i === 2.U(3).asBits)
    val cmdWrite     = io.wb_we_i & (io.wb_adr_i === 4.U(3).asBits)

    parameter.target match
      case I2cTarget.ResetPulse     =>
        // arst_i low takes the `if (~nReset)` branches; releasing it and writing proves the core recovers.
        Generate(Sem.temporal((!io.arst_i).S.##(1, Some(3))((io.arst_i & ctrlWrite).S)), "gen_reset_pulse")
      case I2cTarget.ClockStretch   =>
        // Enable the core and start a transfer, then hold SCL low — the solver plays the stretching slave.
        Generate(
          Sem.temporal(
            (io.arst_i & ctrlWrite & (io.wb_dat_i === 128.U(8).asBits)).S
              .##(1, Some(4))((io.arst_i & cmdWrite).S)
              .##(1, Some(6))(((!io.scl_pad_i) & io.arst_i).S)
          ),
          "gen_clock_stretch"
        )
      case I2cTarget.ArbitrationLost =>
        // SDA reads back low while the core is enabled and a transfer is running.
        //
        // The first anchor programs the prescaler to zero, and it is there because the solver said so: without it
        // the intent came back INFEASIBLE at bound 12, and the reason is in the design — `prer` resets to 0xFFFF,
        // so one SCL period is ~300k core clocks and no bit-level event is reachable in any practical bound. The
        // verdict pointed straight at the missing setup step, which is the sort of thing a run that merely fails
        // to hit coverage cannot tell you.
        val prerWrite = io.wb_we_i & (io.wb_adr_i === 0.U(3).asBits) & (io.wb_dat_i === 0.U(8).asBits)
        Generate(
          Sem.temporal(
            (io.arst_i & prerWrite).S
              .##(1, Some(3))((io.arst_i & ctrlWrite & (io.wb_dat_i === 128.U(8).asBits)).S)
              .##(1, Some(3))((io.arst_i & cmdWrite).S)
              .##(1, Some(10))(((!io.sda_pad_i) & io.scl_pad_i & io.arst_i).S)
          ),
          "gen_arbitration_lost"
        )

    val probe = summon[ProbeInterface[HavenI2cTargetProbe]]
    layer("Verification"):
      Probes.expose(probe.ACK, Bits(1), instance.io.wb_ack_o.asBits)
