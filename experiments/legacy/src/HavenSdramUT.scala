// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Probes, Sem, Txn, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class HavenSdramParameter() extends Parameter

given upickle.default.ReadWriter[HavenSdramParameter] = upickle.default.macroRW

class HavenSdramLayers(parameter: HavenSdramParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** `sdrc_top_split`: the HAVEN benchmark's SDRAM controller (OpenCores sdr_ctrl) with the bidirectional data pad
  * split into `sdr_dq_i`/`sdr_dq_o` (`experiments/fixtures/haven/sdram/sdrc_top_split.v`, the only model-prep
  * change). Wishbone on one side, the SDRAM command bus on the other; every configuration is an input.
  */
class HavenSdramIO(parameter: HavenSdramParameter) extends HWBundle(parameter):
  val sdram_clk        = Flipped(Clock())
  val sdram_resetn     = Flipped(Bool())
  val cfg_sdr_width    = Flipped(Bits(2))
  val cfg_colbits      = Flipped(Bits(2))
  val wb_rst_i         = Flipped(Bool())
  val wb_clk_i         = Flipped(Clock())
  val wb_stb_i         = Flipped(Bool())
  val wb_ack_o         = Aligned(Bool())
  val wb_addr_i        = Flipped(Bits(26))
  val wb_we_i          = Flipped(Bool())
  val wb_dat_i         = Flipped(Bits(32))
  val wb_sel_i         = Flipped(Bits(4))
  val wb_dat_o         = Aligned(Bits(32))
  val wb_cyc_i         = Flipped(Bool())
  val wb_cti_i         = Flipped(Bits(3))
  val sdr_cke          = Aligned(Bool())
  val sdr_cs_n         = Aligned(Bool())
  val sdr_ras_n        = Aligned(Bool())
  val sdr_cas_n        = Aligned(Bool())
  val sdr_we_n         = Aligned(Bool())
  val sdr_dqm          = Aligned(Bits(2))
  val sdr_ba           = Aligned(Bits(2))
  val sdr_addr         = Aligned(Bits(13))
  val sdr_dq_i         = Flipped(Bits(16))
  val sdr_dq_o         = Aligned(Bits(16))
  val sdr_init_done    = Aligned(Bool())
  val cfg_sdr_tras_d   = Flipped(Bits(4))
  val cfg_sdr_trp_d    = Flipped(Bits(4))
  val cfg_sdr_trcd_d   = Flipped(Bits(4))
  val cfg_sdr_en       = Flipped(Bool())
  val cfg_req_depth    = Flipped(Bits(2))
  val cfg_sdr_mode_reg = Flipped(Bits(13))
  val cfg_sdr_cas      = Flipped(Bits(3))
  val cfg_sdr_trcar_d  = Flipped(Bits(4))
  val cfg_sdr_twr_d    = Flipped(Bits(4))
  val cfg_sdr_rfsh     = Flipped(Bits(12))
  val cfg_sdr_rfmax    = Flipped(Bits(3))

class HavenSdramProbe(parameter: HavenSdramParameter) extends DVBundle[HavenSdramParameter, HavenSdramLayers](parameter)

case class HavenSdramVerilogParams() extends VerilogParameter

@generator
object HavenSdram
    extends VerilogWrapper[HavenSdramParameter, HavenSdramLayers, HavenSdramIO, HavenSdramProbe, HavenSdramVerilogParams]:
  def verilogModuleName(parameter:   HavenSdramParameter) = "sdrc_top_split"
  def verilogParameter(parameter:    HavenSdramParameter) = HavenSdramVerilogParams()
  override def moduleName(parameter: HavenSdramParameter): String = verilogModuleName(parameter)

/** Which scenario the flow states; each is a chain of Wishbone requests and an observation on the SDRAM command
  * bus, all over ports.
  */
case class HavenSdramFlowParameter(flow: String) extends Parameter

given upickle.default.ReadWriter[HavenSdramFlowParameter] = upickle.default.macroRW

class HavenSdramFlowLayers(parameter: HavenSdramFlowParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** Drive ports carry HAVEN's `sdrc_top_seq_item` field names; `wb_stb_i` is the request strobe (held until ack, as
  * the driver does), and `sdr_dq_i` is what the SDRAM would drive — free, since the controller's state machines do
  * not depend on read data.
  */
class HavenSdramFlowIO(parameter: HavenSdramFlowParameter) extends HWBundle(parameter):
  val clock     = Flipped(Clock())
  val reset     = Flipped(Reset())
  val wb_stb_i  = Flipped(Bool())
  val wb_addr_i = Flipped(Bits(26))
  val wb_dat_i  = Flipped(Bits(32))
  val wb_sel_i  = Flipped(Bits(4))
  val wb_we_i   = Flipped(Bool())
  val wb_cti_i  = Flipped(Bits(3))
  val cfg_sdr_en = Flipped(Bool()) // the controller enable: the one configuration pin a flow may move
  val sdr_dq_i  = Flipped(Bits(16))
  val ACK       = Aligned(Bool())
  val DAT       = Aligned(Bits(32))
  val INIT      = Aligned(Bool())
  val CMD       = Aligned(Bits(4)) // {cs_n, ras_n, cas_n, we_n}: the SDRAM command the controller issues
  val BA        = Aligned(Bits(2))

class HavenSdramFlowProbe(parameter: HavenSdramFlowParameter)
    extends DVBundle[HavenSdramFlowParameter, HavenSdramFlowLayers](parameter):
  val ACK = ProbeRead(Bits(1), layers("Verification"))

/** Transaction flows on the SDRAM controller, stated over ports and solved by a formal engine that takes unbounded
  * SVA (the JasperGold backend): the controller's initialization, a write, a read, a same-bank row change (which
  * must precharge), the periodic refresh, read-after-write and write-after-read, and the enable withdrawn and
  * restored. The configuration is the one HAVEN's testbench wires as constants,
  * tied here by construction so model and replay agree; SDRAM clock and Wishbone clock are one clock.
  *
  * Commands are observed on the SDRAM bus as {cs_n, ras_n, cas_n, we_n}: ACTIVATE 0011, READ 0101, WRITE 0100,
  * PRECHARGE 0010, REFRESH 0001 — the design's own encoding, and an output the intent can name.
  */
@generator
object HavenSdramFlowUT
    extends Generator[HavenSdramFlowParameter, HavenSdramFlowLayers, HavenSdramFlowIO, HavenSdramFlowProbe]
    with UT[HavenSdramFlowParameter, HavenSdramFlowIO]:
  override def moduleName(p: HavenSdramFlowParameter): String = s"HavenSdramFlowUT_${p.flow}"

  def architecture(parameter: HavenSdramFlowParameter) =
    val io       = summon[Interface[HavenSdramFlowIO]]
    val instance = HavenSdram.instantiate(HavenSdramParameter())
    instance.io.sdram_clk        := io.clock
    instance.io.sdram_resetn     := !io.reset.asBool
    instance.io.wb_clk_i         := io.clock
    instance.io.wb_rst_i         := io.reset.asBool
    instance.io.wb_stb_i         := io.wb_stb_i
    instance.io.wb_cyc_i         := io.wb_stb_i
    instance.io.wb_addr_i        := io.wb_addr_i
    instance.io.wb_we_i          := io.wb_we_i
    instance.io.wb_dat_i         := io.wb_dat_i
    instance.io.wb_sel_i         := io.wb_sel_i
    instance.io.wb_cti_i         := io.wb_cti_i
    instance.io.sdr_dq_i         := io.sdr_dq_i
    // HAVEN's testbench configuration (sdrc_top_top.sv), as constants.
    instance.io.cfg_sdr_en       := io.cfg_sdr_en
    instance.io.cfg_sdr_width    := 1.U(2).asBits
    instance.io.cfg_colbits      := 1.U(2).asBits
    instance.io.cfg_sdr_mode_reg := 51.U(13).asBits // 13'h033
    instance.io.cfg_sdr_tras_d   := 5.U(4).asBits
    instance.io.cfg_sdr_trp_d    := 2.U(4).asBits
    instance.io.cfg_sdr_trcd_d   := 2.U(4).asBits
    instance.io.cfg_sdr_cas      := 3.U(3).asBits
    instance.io.cfg_sdr_trcar_d  := 8.U(4).asBits
    instance.io.cfg_sdr_twr_d    := 2.U(4).asBits
    instance.io.cfg_sdr_rfsh     := 780.U(12).asBits
    instance.io.cfg_sdr_rfmax    := 4.U(3).asBits
    instance.io.cfg_req_depth    := 2.U(2).asBits
    io.ACK  := instance.io.wb_ack_o
    io.DAT  := instance.io.wb_dat_o
    io.INIT := instance.io.sdr_init_done
    io.CMD  := instance.io.sdr_cs_n.asBits ## instance.io.sdr_ras_n.asBits ## instance.io.sdr_cas_n.asBits ##
      instance.io.sdr_we_n.asBits
    io.BA   := instance.io.sdr_ba

    Txn.assumeResetLow(io.reset)

    given ClockEvent = posedge(io.clock)
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
    // The flow starts at the cycle after reset exactly: the first thing it demands is silence, which is always
    // satisfiable there, and a wider window only lets a free request precede the flow.
    val fresh        = Txn.firstCycle()

    val en        = io.cfg_sdr_en
    val idle      = !io.wb_stb_i & en
    val initDone  = instance.io.sdr_init_done
    val ack       = instance.io.wb_ack_o
    def cmdIs(c: Int) = io.CMD === c.U(4).asBits
    val precharge = cmdIs(2)
    val refresh   = cmdIs(1)
    val writeCmd  = cmdIs(4)
    val readCmd   = cmdIs(5)
    // A request: strobe with full byte enables and a classic (non-burst) cycle, held until the acknowledge.
    val reqW      = en & io.wb_stb_i & io.wb_we_i & (io.wb_sel_i === 15.U(4).asBits) & (io.wb_cti_i === 0.U(3).asBits)
    val reqR      = en & io.wb_stb_i & !io.wb_we_i & (io.wb_sel_i === 15.U(4).asBits) & (io.wb_cti_i === 0.U(3).asBits)
    def held(req: Referable[Bool]) = req.throughout(req.S.##+((req & ack).S))
    // A request held until ack, with a command observed on the SDRAM bus *while* it is held — a read's ack carries
    // the data, so its READ command precedes the ack (stated after it, the read flow is unreachable: JasperGold's
    // proof said so in 530 s, and it was right).
    def heldSeeing(req: Referable[Bool], seen: Referable[Bool]) =
      req.throughout(req.S.##+((req & seen).S).##+((req & ack).S))
    // Nothing requested from reset until the controller reports its initialization done.
    val init      = (fresh & idle).S ### idle.throughout(idle.S.##+((idle & initDone).S))
    def eventually(seen: Referable[Bool]) = idle.throughout(idle.S.##+((idle & seen).S))

    val chain = parameter.flow match
      case "init"      => init
      case "write"     => init ### held(reqW) ### eventually(writeCmd)
      case "read"      => init ### heldSeeing(reqR, readCmd) ### idle.S
      // Two writes whose second must precharge: the first's WRITE must reach the bus first, or the initialization's
      // own PRECHARGE satisfies the anchor (it did — a 175-cycle witness with no ACTIVATE or WRITE in it).
      case "precharge" => init ### held(reqW) ### eventually(writeCmd) ### held(reqW) ### eventually(precharge) ### eventually(writeCmd)
      case "refresh"   => init ### eventually(refresh)
      // Read after write and write after read: the transfer controller's turnaround transitions.
      case "wrrd"      => init ### held(reqW) ### eventually(writeCmd) ### heldSeeing(reqR, readCmd) ### idle.S
      case "rdwr"      => init ### heldSeeing(reqR, readCmd) ### held(reqW) ### eventually(writeCmd)
      // The enable withdrawn mid-operation and restored: the management machine's power-up transitions, and a
      // second initialization. `off` is the only shape in which a flow moves a configuration pin.
      case "powerdown" =>
        val off = !io.wb_stb_i & !en
        init ### held(reqW) ### off.throughout(off.S.##+((off & !initDone).S)) ### idle.throughout(idle.S.##+((idle & initDone).S))
      case other       => throw IllegalArgumentException(s"unknown sdram flow '$other'")
    Generate(Sem.temporal(chain), s"sdram_${parameter.flow}")

    val probe = summon[ProbeInterface[HavenSdramFlowProbe]]
    layer("Verification"):
      Probes.expose(probe.ACK, Bits(1), instance.io.wb_ack_o.asBits)
