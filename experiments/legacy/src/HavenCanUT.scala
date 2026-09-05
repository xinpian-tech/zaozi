// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Probes, Sem, Txn, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class HavenCanParameter() extends Parameter

given upickle.default.ReadWriter[HavenCanParameter] = upickle.default.macroRW

class HavenCanLayers(parameter: HavenCanParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** `can_top`, the OpenCores CAN controller in its Wishbone build (`CAN_WISHBONE_IF` in can_defines.v): an 8-bit
  * register bus on one side, the CAN bus (`rx_i`/`tx_o`) on the other. Vendored unmodified in
  * `experiments/fixtures/haven/can/`.
  */
class HavenCanIO(parameter: HavenCanParameter) extends HWBundle(parameter):
  val wb_clk_i   = Flipped(Clock())
  val wb_rst_i   = Flipped(Bool())
  val wb_dat_i   = Flipped(Bits(8))
  val wb_dat_o   = Aligned(Bits(8))
  val wb_cyc_i   = Flipped(Bool())
  val wb_stb_i   = Flipped(Bool())
  val wb_we_i    = Flipped(Bool())
  val wb_adr_i   = Flipped(Bits(8))
  val wb_ack_o   = Aligned(Bool())
  val clk_i      = Flipped(Clock())
  val rx_i       = Flipped(Bool())
  val tx_o       = Aligned(Bool())
  val bus_off_on = Aligned(Bool())
  val irq_on     = Aligned(Bool())
  val clkout_o   = Aligned(Bool())

class HavenCanProbe(parameter: HavenCanParameter) extends DVBundle[HavenCanParameter, HavenCanLayers](parameter)

case class HavenCanVerilogParams() extends VerilogParameter

@generator
object HavenCan extends VerilogWrapper[HavenCanParameter, HavenCanLayers, HavenCanIO, HavenCanProbe, HavenCanVerilogParams]:
  def verilogModuleName(parameter:   HavenCanParameter) = "can_top"
  def verilogParameter(parameter:    HavenCanParameter) = HavenCanVerilogParams()
  override def moduleName(parameter: HavenCanParameter): String = verilogModuleName(parameter)

/** `btr1` is bus timing register 1 (TSEG1 in [3:0], TSEG2 in [6:4]): 0x00 is the shortest bit the core accepts, three
  * time quanta of two clocks; 0x14 is a conventional 8-quantum bit. Shorter bits mean shorter witnesses.
  */
case class HavenCanFlowParameter(flow: String, btr1: Int = 0x00, partner: String = "free") extends Parameter

given upickle.default.ReadWriter[HavenCanFlowParameter] = upickle.default.macroRW

class HavenCanFlowLayers(parameter: HavenCanFlowParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** Drive ports: the Wishbone register access (HAVEN's `seq_item` names) and `rx` — what the *other* CAN node drives
  * onto the bus. The bus is a wired-AND of the controller's `tx_o` and that drive, so the engine plays the second
  * node: it acknowledges the controller's frames, sends frames of its own, or corrupts them.
  */
class HavenCanFlowIO(parameter: HavenCanFlowParameter) extends HWBundle(parameter):
  val clock    = Flipped(Clock())
  val reset    = Flipped(Reset())
  val wb_stb_i = Flipped(Bool())
  val wb_adr_i = Flipped(Bits(8))
  val wb_dat_i = Flipped(Bits(8))
  val wb_we_i  = Flipped(Bool())
  val rx       = Flipped(Bool())
  val ACK      = Aligned(Bool())
  val DAT      = Aligned(Bits(8))
  val TX       = Aligned(Bool())
  val IRQ      = Aligned(Bool())
  val BUSOFF   = Aligned(Bool())

class HavenCanFlowProbe(parameter: HavenCanFlowParameter)
    extends DVBundle[HavenCanFlowParameter, HavenCanFlowLayers](parameter):
  val ACK = ProbeRead(Bits(1), layers("Verification"))

/** Flows on the CAN controller (BasicCAN register map: 0 control, 1 command, 2 status, 6/7 bus timing, 10.. transmit
  * buffer), each an unbounded chain over the register bus with the CAN bus left to the engine:
  *   - `leave_reset`: bus timing programmed in reset mode, reset request cleared, read back.
  *   - `transmit`: a zero-length frame requested; the start-of-frame observed dominant on `tx_o`; the transmit
  *     interrupt — which needs the other node's acknowledge, and the engine supplies it.
  *   - `receive`: with the receive interrupt enabled, the interrupt — the engine has to send a whole valid frame.
  *   - `busoff`: `bus_off_on` — the engine has to drive the controller through 256 error counts.
  * The bit time defaults to the shortest the core allows (BRP 0, one quantum each: 6 clocks a bit).
  */
@generator
object HavenCanFlowUT
    extends Generator[HavenCanFlowParameter, HavenCanFlowLayers, HavenCanFlowIO, HavenCanFlowProbe]
    with UT[HavenCanFlowParameter, HavenCanFlowIO]:
  override def moduleName(p: HavenCanFlowParameter): String = s"HavenCanFlowUT_${p.flow}"

  def architecture(parameter: HavenCanFlowParameter) =
    val io       = summon[Interface[HavenCanFlowIO]]
    val instance = HavenCan.instantiate(HavenCanParameter())
    instance.io.wb_clk_i := io.clock
    instance.io.clk_i    := io.clock
    instance.io.wb_rst_i := io.reset.asBool
    instance.io.wb_stb_i := io.wb_stb_i
    instance.io.wb_cyc_i := io.wb_stb_i
    instance.io.wb_adr_i := io.wb_adr_i
    instance.io.wb_dat_i := io.wb_dat_i
    instance.io.wb_we_i  := io.wb_we_i
    // The other node on the bus. `free`: whatever the engine drives, every cycle. `ack_once`: a node that does
    // exactly one thing — drives dominant for one bit time, once, when the engine says so (`rx` becomes that
    // request) — which is what an acknowledging receiver is, and leaves the engine one choice, the moment, instead
    // of one per cycle. With the drive free the acknowledged-frame cover sat undetermined for 900 s.
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
    val partnerDrive = parameter.partner match
      case "free"     => io.rx
      case "ack_once" =>
        val cnt    = RegInit(0.U(4))
        val fired  = RegInit(false.B)
        val active = !(cnt === 0.U(4))
        val start  = io.rx & !fired & !active
        cnt   := (active ? ((cnt - 1.U(4)).asBits.bits(3, 0).asUInt, (start ? (6.U(4), 0.U(4)))))
        fired := fired | start
        !active
      case other      => throw IllegalArgumentException(s"unknown partner '$other'")
    instance.io.rx_i     := instance.io.tx_o & partnerDrive // the bus: dominant wins
    io.ACK    := instance.io.wb_ack_o
    io.DAT    := instance.io.wb_dat_o
    io.TX     := instance.io.tx_o
    io.IRQ    := instance.io.irq_on
    io.BUSOFF := instance.io.bus_off_on

    Txn.assumeResetLow(io.reset)

    given ClockEvent = posedge(io.clock)
    val fresh        = Txn.firstCycle()

    val ack  = instance.io.wb_ack_o
    val idle = !io.wb_stb_i
    def write(addr: Int, data: Int) =
      io.wb_stb_i & io.wb_we_i & (io.wb_adr_i === addr.U(8).asBits) & (io.wb_dat_i === data.U(8).asBits)
    def read(addr: Int)          = io.wb_stb_i & !io.wb_we_i & (io.wb_adr_i === addr.U(8).asBits)
    def held(req: Referable[Bool])   = req.throughout(req.S.##+((req & ack).S))
    def readSees(addr: Int, pred: Referable[Bool]) =
      read(addr).throughout(read(addr).S.##+((read(addr) & ack & pred).S))
    def eventually(seen: Referable[Bool]) = idle.throughout(idle.S.##+((idle & seen).S))

    // Reset mode is the power-on state; timing registers accept writes only there. Control register: bit 0 reset
    // request, bit 1 receive interrupt enable, bit 2 transmit interrupt enable.
    def leaveReset(control: Int) =
      (fresh & idle).S ### held(write(6, 0x00)) ### held(write(7, parameter.btr1)) ### held(write(0, control))
        ### readSees(0, !io.DAT.bit(0))
    // Polarity, from the RTL: `irq_on` is the core's active-low `irq_n`, and `bus_off_on` is high while the node is
    // ON the bus (it rises when reset mode is left, and falls at bus-off). The first witnesses read both the wrong
    // way round and were satisfied trivially: an interrupt line idle high, and a node merely coming onto the bus.
    // The interrupt line alone is not a completion: the engine, playing the other node, drove the bus dominant
    // against the controller's recessive bits and raised an error-frame interrupt within four cycles of the start
    // of frame. Completion is the interrupt *register* (address 3): bit 1 is set only by a successful
    // transmission, bit 0 only by a correctly received frame; an error frame sets neither, and retransmission
    // after one is legal bus behaviour the engine may still choose.
    def later(seq: Sequence) = idle.S.*(1, None) ### seq
    val chain = parameter.flow match
      case "leave_reset" => leaveReset(0x00)
      // The frame's start on the bus (a request, then `tx_o` dominant), and the whole frame acknowledged (the
      // transmit interrupt): two flows, because the second needs the engine to place the other node's
      // acknowledge in the one bit slot where it counts.
      case "sof"         =>
        leaveReset(0x04) ### held(write(10, 0xa5)) ### held(write(11, 0x00)) ### held(write(1, 0x01))
          ### eventually(!io.TX)
      case "transmit"    =>
        leaveReset(0x04) ### held(write(10, 0xa5)) ### held(write(11, 0x00)) ### held(write(1, 0x01))
          ### eventually(!io.TX) ### later(readSees(3, io.DAT.bit(1)))
      case "receive"     => leaveReset(0x02) ### later(readSees(3, io.DAT.bit(0)))
      // Bus-off is the transmitter's fate, not a receiver's: only the transmit error counter reaches 256, so the
      // node must be asked to transmit and the other node must keep wrecking the frames. Stated without the
      // request, JasperGold proved it unreachable in 10 s.
      case "busoff"      =>
        leaveReset(0x04) ### held(write(10, 0xa5)) ### held(write(11, 0x00)) ### held(write(1, 0x01))
          ### eventually(!io.BUSOFF)
      case other         => throw IllegalArgumentException(s"unknown can flow '$other'")
    Generate(Sem.temporal(chain), s"can_${parameter.flow}")

    val probe = summon[ProbeInterface[HavenCanFlowProbe]]
    layer("Verification"):
      Probes.expose(probe.ACK, Bits(1), instance.io.wb_ack_o.asBits)
