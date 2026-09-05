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
  val wb_clk_i     = Flipped(Clock())
  val wb_rst_i     = Flipped(Bool())
  val arst_i       = Flipped(Bool())
  val wb_adr_i     = Flipped(Bits(3))
  val wb_dat_i     = Flipped(Bits(8))
  val wb_we_i      = Flipped(Bool())
  val wb_stb_i     = Flipped(Bool())
  val wb_cyc_i     = Flipped(Bool())
  val scl_pad_i    = Flipped(Bool())
  val sda_pad_i    = Flipped(Bool())
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
  def verilogModuleName(parameter:   HavenI2cParameter) = "i2c_master_top"
  def verilogParameter(parameter:    HavenI2cParameter) = HavenI2cVerilogParams()
  override def moduleName(parameter: HavenI2cParameter): String = verilogModuleName(parameter)

/** `ctrlValue` is the byte an intent wants written to the control register (address 2): bit 7 enables the core, bit 6
  * enables interrupts.
  */
case class HavenI2cCtrlParameter(ctrlValue: Int) extends Parameter

given upickle.default.ReadWriter[HavenI2cCtrlParameter] = upickle.default.macroRW

class HavenI2cCtrlLayers(parameter: HavenI2cCtrlParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** Drive ports carry the same names and widths as HAVEN's `i2c_master_top_seq_item` fields, so a witness renders
  * straight into its sequence. `stb`/`cyc` are held asserted here — the replay driver performs the full handshake per
  * transaction, so what the intent describes is the *sequence of register accesses*.
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

/** First i2c intent, deliberately minimal: a write of `ctrlValue` to the control register. Its purpose is to answer the
  * scaling question — whether circt-bmc solves at all through a three-module I2C hierarchy — before anything more
  * ambitious is attempted.
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
  * Its transaction carries `constraint c_reset { arst_i == 1'b0; }`, pinning the asynchronous reset, so no sequence it
  * generates can ever exercise the `if (~nReset)` branches — they are unreachable by construction of its own
  * constraint, not by anything about the design. Here `arst_i` is simply an input the intent may name.
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

/** Which residual class an intent targets. `nReset` in bit_ctrl is `arst_i` (ARST_LVL is 0, so the XOR is the identity)
  * and its reset branches are `if (~nReset)`, i.e. taken while `arst_i` is LOW.
  */
enum I2cTarget:
  /** The `if (~nReset)` branches: pull arst_i low, then release it and do a register write. */
  case ResetPulse

  /** The `slave_wait` branch of the clock-enable generator: a slave holding SCL low stretches the clock. Reaching it
    * needs bus behaviour, which is why HAVEN needs a slave BFM and a solver does not.
    */
  case ClockStretch

  /** Arbitration lost: SDA reads back low while the master is releasing it. */
  case ArbitrationLost

  /** A complete transfer's *setup*: the register flow that starts one — prescaler, enable, slave address, then
    * START|WRITE — after which the byte and bit controllers run the transfer on their own for hundreds of cycles. The
    * intent describes only the writes, because those are the stimulus; the protocol activity that covers byte_ctrl and
    * bit_ctrl is the design's response to them, not something the testbench drives.
    */
  case FullTransfer

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

    given ClockEvent                = posedge(io.clock)
    val ctrlWrite                   = io.wb_we_i & (io.wb_adr_i === 2.U(3).asBits)
    val cmdWrite                    = io.wb_we_i & (io.wb_adr_i === 4.U(3).asBits)
    def write(addr: Int, data: Int) =
      io.arst_i & io.wb_we_i & (io.wb_adr_i === addr.U(3).asBits) & (io.wb_dat_i === data.U(8).asBits)

    parameter.target match
      case I2cTarget.ResetPulse      =>
        // arst_i low takes the `if (~nReset)` branches; releasing it and writing proves the core recovers.
        Generate(Sem.temporal((!io.arst_i).S.##(1, Some(3))((io.arst_i & ctrlWrite).S)), "gen_reset_pulse")
      case I2cTarget.ClockStretch    =>
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

      case I2cTarget.FullTransfer =>
        // OpenCores I2C register map: 0/1 = prescaler, 2 = control (bit7 enables), 3 = transmit,
        // 4 = command (STA 0x80 | WR 0x10). Five anchors, spacing chosen by the solver.
        //
        // 0xA0 is the BFM's 7-bit slave address 0x50 shifted left with the write bit clear: the intent has to name
        // the address the environment's slave model answers to, or the transfer is NACKed immediately.
        //
        // `throughout` is load-bearing, not decoration. Constraining arst_i only at the anchors leaves it free in
        // the solver-chosen gaps, and a free variable is an adversarial one — the first version of this intent came
        // back with reset asserted in 72 of 98 beats, holding the core in reset right after the command write, so
        // no transfer ever ran. Under-specification cuts both ways: an invariant that must hold across the gaps has
        // to be stated as one.
        // Consecutive steps, no range delays. `throughout` would say this directly but yields an !ltl.property,
        // which circt-bmc refuses to lower; back-to-back `###` leaves no unconstrained cycle for the solver to put
        // the core back into reset, at the cost of the under-specification a gap would have allowed.
        Generate(
          Sem.temporal(
            write(0, 0x01).S ### write(1, 0x00).S ### write(2, 0x80).S ### write(3, 0xa0).S ### write(4, 0x90).S
          ),
          "gen_full_transfer_setup"
        )

    val probe = summon[ProbeInterface[HavenI2cTargetProbe]]
    layer("Verification"):
      Probes.expose(probe.ACK, Bits(1), instance.io.wb_ack_o.asBits)

/** One intent per register address: a write to that address with the core out of reset. The data byte is left
  * unconstrained, so the emitted sequence pins the address and randomizes the payload — volume with structure, the same
  * shape the ALU experiment used.
  */
case class HavenI2cRegParameter(addr: Int, isWrite: Boolean = true) extends Parameter

given upickle.default.ReadWriter[HavenI2cRegParameter] = upickle.default.macroRW

class HavenI2cRegLayers(parameter: HavenI2cRegParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class HavenI2cRegIO(parameter: HavenI2cRegParameter) extends HWBundle(parameter):
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

class HavenI2cRegProbe(parameter: HavenI2cRegParameter)
    extends DVBundle[HavenI2cRegParameter, HavenI2cRegLayers](parameter):
  val ACK = ProbeRead(Bits(1), layers("Verification"))

@generator
object HavenI2cRegUT
    extends Generator[HavenI2cRegParameter, HavenI2cRegLayers, HavenI2cRegIO, HavenI2cRegProbe]
    with UT[HavenI2cRegParameter, HavenI2cRegIO]:
  override def moduleName(p: HavenI2cRegParameter): String =
    s"HavenI2cRegUT_a${p.addr}_" + (if p.isWrite then "w" else "r")

  def architecture(parameter: HavenI2cRegParameter) =
    val io       = summon[Interface[HavenI2cRegIO]]
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
        io.arst_i & (if parameter.isWrite then io.wb_we_i else !io.wb_we_i) &
          (io.wb_adr_i === parameter.addr.U(3).asBits)
      ),
      s"gen_reg_${if parameter.isWrite then "write" else "read"}_${parameter.addr}"
    )

    val probe = summon[ProbeInterface[HavenI2cRegProbe]]
    layer("Verification"):
      Probes.expose(probe.ACK, Bits(1), instance.io.wb_ack_o.asBits)

/** A transaction-flow intent: the register writes that set up the core and launch one I2C command, then the wait for
  * its completion — a chain of dependent Wishbone accesses rather than one access.
  *
  * `cmd` is the command byte written to CR (STA 0x80 | STO 0x40 | RD 0x20 | WR 0x10 | ACK 0x08) and `txr` the byte
  * loaded into TXR first (the slave address with its R/W bit, or a data byte). byte_ctrl's state machine is driven by
  * exactly these bits and observed through SR, so every one of its protocol transitions is a flow with a different
  * command byte — no reference to the state register itself is needed. `maxWait` bounds the polling gap the solver may
  * leave between the command and the completion it has to observe.
  *
  * `prer` defaults to 2, the smallest prescaler at which this core completes a WRITE byte: at 0 a bit phase is one
  * clock, shorter than bit_ctrl's input synchronizer, and the master reads its own previous bit back and loses
  * arbitration to itself — in JasperGold's model and in a VCS simulation of the same wrapper alike (al at cycle 47
  * at prescaler 0; done at cycle 232 at prescaler 2). A command at prescaler 2 is ~220 cycles, hence `maxWait`.
  */
case class HavenI2cFlowParameter(
  label:       String,
  txr:         Int,
  cmd:         Int,
  maxWait:     Int = 300,
  prer:        Int = 2,
  resetMidway: Boolean = false,
  minWait:     Int = 1,
  gapStyle:    String = "repeat")
    extends Parameter

given upickle.default.ReadWriter[HavenI2cFlowParameter] = upickle.default.macroRW

class HavenI2cFlowLayers(parameter: HavenI2cFlowParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** Same drive signature as the other i2c UTs, so flow witnesses concatenate with theirs into one sequence. */
class HavenI2cFlowIO(parameter: HavenI2cFlowParameter) extends HWBundle(parameter):
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

class HavenI2cFlowProbe(parameter: HavenI2cFlowParameter)
    extends DVBundle[HavenI2cFlowParameter, HavenI2cFlowLayers](parameter):
  val ACK = ProbeRead(Bits(1), layers("Verification"))

/** C = "prescaler to zero, core enabled, TXR loaded, `cmd` issued, and the interrupt flag observed to rise" — the
  * completion is what the flow waits for, and the transfer that byte_ctrl and bit_ctrl run in between is the design's
  * response to the writes.
  *
  * Constrained by construction, not by assumption (circt-bmc does not enforce an assume on a port feeding the
  * instance): the bus is looped back through the wrapper (a released line reads as pulled-up, a driven line reads its
  * own value), so the solver cannot abort the transfer by faking arbitration loss or clock stretching, and the flow's
  * own beats pin `arst_i` high and keep the gap cycles to status-register reads. A witness therefore replays through a
  * Wishbone driver as writes and polls, in order, with the transfer's own timing supplied by the DUT.
  */
@generator
object HavenI2cFlowUT
    extends Generator[HavenI2cFlowParameter, HavenI2cFlowLayers, HavenI2cFlowIO, HavenI2cFlowProbe]
    with UT[HavenI2cFlowParameter, HavenI2cFlowIO]:
  override def moduleName(p: HavenI2cFlowParameter): String = s"HavenI2cFlowUT_${p.label}"

  def architecture(parameter: HavenI2cFlowParameter) =
    val io       = summon[Interface[HavenI2cFlowIO]]
    val instance = HavenI2c.instantiate(HavenI2cParameter())
    instance.io.wb_clk_i  := io.clock
    instance.io.wb_rst_i  := io.reset.asBool
    instance.io.arst_i    := io.arst_i
    instance.io.wb_adr_i  := io.wb_adr_i
    instance.io.wb_dat_i  := io.wb_dat_i
    instance.io.wb_we_i   := io.wb_we_i
    instance.io.wb_stb_i  := true.B
    instance.io.wb_cyc_i  := true.B
    // Bus loopback: the master sees the level it drives, or a pull-up when it releases the line.
    instance.io.scl_pad_i := instance.io.scl_padoen_o ? (true.B, instance.io.scl_pad_o)
    instance.io.sda_pad_i := instance.io.sda_padoen_o ? (true.B, instance.io.sda_pad_o)
    io.ACK                := instance.io.wb_ack_o
    io.DAT                := instance.io.wb_dat_o

    Txn.assumeResetLow(io.reset)

    given ClockEvent                = posedge(io.clock)
    // A write lands only on a cycle the core acknowledges: with stb/cyc held high `wb_ack_o` alternates, so a write
    // beat must coincide with ACK high, and two writes are two beats apart with a poll between. Without the ACK
    // term a chain of back-to-back writes is half dropped by the model — JasperGold showed the witness pre-writing
    // the dropped registers in the unconstrained prefix instead — while the replay driver's per-transaction
    // handshake lands every one of them, so model and replay would disagree about which writes happened.
    def write(addr: Int, data: Int) =
      io.arst_i & io.ACK & io.wb_we_i & (io.wb_adr_i === addr.U(3).asBits) & (io.wb_dat_i === data.U(8).asBits)
    // A gap beat: no write, the status register on the address lines, reset released. `wb_dat_o` mirrors SR one
    // cycle after the address is presented, so `DAT` is the status the previous beat asked for — which is why the
    // completion anchor must follow a calm beat and not a free one: a free beat can present the prescaler register,
    // whose 0xFF high byte reads back with bit 0 set, and JasperGold found that reading in 0.09 s.
    val calm                        = io.arst_i & !io.wb_we_i & (io.wb_adr_i === 4.U(3).asBits)
    // SR.IF with SR.AL clear: the interrupt flag also rises on arbitration loss, and with the bus looped back the
    // core can lose arbitration to itself when the prescaler is below what its input synchronizers need — JasperGold's
    // first witness at prescaler zero completed that way. A flow completes only when the command finished.
    val completed = io.DAT.bit(0) & !io.DAT.bit(5)

    // The flow starts on the first cycle after reset. Left free, the beats before the chain are an adversary's:
    // JasperGold used them to run a STOP command whose completion then satisfied the chain's own completion anchor.
    // "First cycles" is two wide, not one: the core acknowledges on alternate cycles and does not acknowledge the
    // cycle after reset, so a flow pinned to cycle 0 exactly is unsatisfiable — JasperGold proved that cover
    // unreachable in 2 s where the assertion form had sat undetermined for 10 minutes.
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
    val boot         = RegInit(0.U(2))
    boot := ((boot === 2.U(2)) ? (boot, (boot + 1.U(2)).asBits.bits(1, 0).asUInt))
    val fresh        = boot < 2.U(2)
    val setup        =
      (fresh & write(0, parameter.prer)).S ### calm.S ### write(1, 0x00).S ### calm.S ### write(2, 0x80).S ### calm.S
        ### write(3, parameter.txr).S ### calm.S ### write(4, parameter.cmd).S

    if parameter.resetMidway then
      // The transfer is interrupted: after a solver-chosen number of polls, the asynchronous reset is pulled low
      // for one beat and released. Where in the transfer that lands is what the intent leaves open, and the
      // replay sweeps it — `prer` widens a bit phase to the driver's per-transaction cost, so a sweep over the
      // poll count visits every phase. No completion is waited for; the reset is the event.
      val rst = !io.arst_i & !io.wb_we_i & (io.wb_adr_i === 4.U(3).asBits)
      Generate(
        Sem.temporal(setup ### calm.S.*(1, Some(parameter.maxWait)) ### rst.S ### calm.S ### calm.S ### calm.S),
        s"flow_${parameter.label}"
      )
    else
      // The wait for completion, in the shape the parameter asks for: a repeated calm beat (every gap cycle
      // constrained), a bare delay (gap cycles free), or an exact count.
      val tail = parameter.gapStyle match
        // Structure only: the writes that launch the command. The transfer they start takes ~50 cycles at
        // prescaler zero, past what the bounded model checker solves on this design in practical time (measured:
        // no verdict after 2h at bound 68), so completion is not proven here — the replay appends status polls (a
        // timing fill) and the simulation observes SR.IF.
        case "none"       => setup
        // The three shapes below are SVA the bounded model checker has no lowering for — an unbounded repetition,
        // `throughout`, a goto repetition — and read naturally to a formal engine that has one. They state the
        // same scenario as "repeat" with the bound removed: the wait is however long the design takes.
        case "unbounded"  => setup ### (calm & !completed).S.*(1, None) ### (calm & completed).S
        case "throughout" => setup ### calm.throughout(calm.S.##+((calm & completed).S))
        case "goto"       =>
          // The transfer must be *seen* in progress: three status reads showing TIP, at any spacing, before the
          // completion — and quiet polling throughout.
          val tip = io.DAT.bit(1)
          setup ### calm.throughout((calm & tip).S.*->(3, 3).##+((calm & completed).S))
        case "delay"      => setup ### calm.S.##(parameter.minWait, Some(parameter.maxWait))((calm & completed).S)
        case _ if parameter.minWait == parameter.maxWait =>
          setup ### (calm & !completed).S.*(parameter.minWait) ### (calm & completed).S
        case _                                           =>
          setup ### (calm & !completed).S.*(parameter.minWait, Some(parameter.maxWait)) ### (calm & completed).S
      Generate(Sem.temporal(tail), s"flow_${parameter.label}")

    val probe = summon[ProbeInterface[HavenI2cFlowProbe]]
    layer("Verification"):
      Probes.expose(probe.ACK, Bits(1), instance.io.wb_ack_o.asBits)
