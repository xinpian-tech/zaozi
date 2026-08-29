// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import com.vowstar.ditdah32.{DitDah32Module, DitDah32Parameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import upickle.default.ReadWriter

import java.lang.foreign.Arena

/** The real core: a DitDah32 RV32EC (vendored under `ditdah32/`, AXI4-Lite memory port) behind a Lite→AXI4 shim that
  * widens onto the 128-bit fabric. Every core access is a 32-bit single-beat transfer: the shim rides it in the
  * addressed word lane with id 0, len 0, size 4 bytes. The write data channel is held until its address has been seen,
  * so the lane is always known. Interrupt inputs are tied off.
  *
  * With `enableDebug` the core carries its debug port too, and the shim maps it onto the [[DebugHartBundle]] the debug
  * module speaks — the same adapter role it plays for the memory port.
  */

case class CoreDeviceP(
  resetPc:     Int,
  addrBits:    Int,
  dataBits:    Int,
  idBits:      Int,
  enableDebug: Boolean,
  enableTrace: Boolean)
    extends Parameter derives ReadWriter:
  require(dataBits == 32 || dataBits == 128, s"the core shim rides a 32- or 128-bit fabric, got dataBits $dataBits")
  require(addrBits >= 1 && addrBits <= 32, s"the core addresses at most a 32-bit space, got addrBits $addrBits")
  require((resetPc & 0x3) == 0 && resetPc >= 0, s"resetPc 0x${resetPc.toHexString} must be 32-bit aligned")
  val xlen:         Int = CoreDeviceP.xlen
  val regIndexBits: Int = CoreDeviceP.regIndexBits

object CoreDeviceP:
  /** The vendored core's fixed facts, which its trace is shaped by. */
  val xlen:         Int = 32
  val regIndexBits: Int = 4 // RV32E: sixteen registers

class CoreDevicePLayers(p: CoreDeviceP) extends LayerInterface(p):
  def layers = if p.enableTrace then Seq(Layer("DV")) else Seq.empty

/** The hart's trace, forwarded from the vendored core and confined to the same layer it colours its probes with. The
  * field order and widths are [[me.jiuyang.syntheke.circt.tests.RvTrace]]'s — the binding checkpoint compares them.
  */
class CoreDevicePProbe(parameter: CoreDeviceP) extends DVBundle[CoreDeviceP, CoreDevicePLayers](parameter):
  private def dv          = layers("DV")
  private def word        = UInt(parameter.xlen)
  val trace_valid               = Option.when(parameter.enableTrace)(ProbeRead(Bool(), dv))
  val trace_pc                  = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_nextPc              = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_instr               = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_len                 = Option.when(parameter.enableTrace)(ProbeRead(UInt(3), dv))
  val trace_rdWe                = Option.when(parameter.enableTrace)(ProbeRead(Bool(), dv))
  val trace_rd                  = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.regIndexBits), dv))
  val trace_rdWdata             = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_rs1Addr             = Option.when(parameter.enableTrace)(ProbeRead(UInt(5), dv))
  val trace_rs1Rdata            = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_rs2Addr             = Option.when(parameter.enableTrace)(ProbeRead(UInt(5), dv))
  val trace_rs2Rdata            = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_memAddr             = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_memRmask            = Option.when(parameter.enableTrace)(ProbeRead(UInt(4), dv))
  val trace_memWmask            = Option.when(parameter.enableTrace)(ProbeRead(UInt(4), dv))
  val trace_memRdata            = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_memWdata            = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_memFault            = Option.when(parameter.enableTrace)(ProbeRead(Bool(), dv))
  val trace_memFaultRmask       = Option.when(parameter.enableTrace)(ProbeRead(UInt(4), dv))
  val trace_memFaultWmask       = Option.when(parameter.enableTrace)(ProbeRead(UInt(4), dv))
  val trace_csrAddr             = Option.when(parameter.enableTrace)(ProbeRead(UInt(12), dv))
  val trace_csrRmask            = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_csrWmask            = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_csrRdata            = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_csrWdata            = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_trap                = Option.when(parameter.enableTrace)(ProbeRead(Bool(), dv))
  val trace_trapCause           = Option.when(parameter.enableTrace)(ProbeRead(UInt(4), dv))
  val trace_mstatus             = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_mstatusPostCommit   = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_mstatusPreTrap      = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_mie                 = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_mtvec               = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_mepc                = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_mtval               = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_mip                 = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_mcause              = Option.when(parameter.enableTrace)(ProbeRead(word, dv))
  val trace_irqPendingMask      = Option.when(parameter.enableTrace)(ProbeRead(word, dv))

class CoreDevicePIO(p: CoreDeviceP) extends HWBundle(p):
  val clk   = Flipped(new ClockBundle)
  val mem   = Aligned(new Axi4Bundle(AxiShape(p.addrBits, p.dataBits, p.idBits)))
  val debug = Option.when(p.enableDebug)(Flipped(new DebugHartBundle(p.xlen)))

@generator
object CoreDeviceGen extends Generator[CoreDeviceP, CoreDevicePLayers, CoreDevicePIO, CoreDevicePProbe]:
  def architecture(p: CoreDeviceP) =
    val io           = summon[Interface[CoreDevicePIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    val core = DitDah32Module.instantiate(
      DitDah32Parameter(resetVector = p.resetPc, enableTrace = p.enableTrace, enableDebug = p.enableDebug)
    )
    core.io.clock        := io.clk.clock
    core.io.reset        := io.clk.reset
    core.io.irq.software := false.B
    core.io.irq.timer    := false.B
    core.io.irq.external := false.B

    // ---- trace: the hart's probes forwarded to the shim's own, one reference each ----
    if p.enableTrace then
      layer("DV"):
        val probe = summon[ProbeInterface[CoreDevicePProbe]]
        val t     = core.probe
        def word = UInt(p.xlen)
        val validW = Wire(Bool())
        validW <== t.trace_valid.get
        probe.trace_valid.get <== validW
        val pcW = Wire(word)
        pcW <== t.trace_pc.get
        probe.trace_pc.get <== pcW
        val nextPcW = Wire(word)
        nextPcW <== t.trace_next_pc.get
        probe.trace_nextPc.get <== nextPcW
        val instrW = Wire(word)
        instrW <== t.trace_instr.get
        probe.trace_instr.get <== instrW
        val lenW = Wire(UInt(3))
        lenW <== t.trace_len.get
        probe.trace_len.get <== lenW
        val rdWeW = Wire(Bool())
        rdWeW <== t.trace_rd_we.get
        probe.trace_rdWe.get <== rdWeW
        val rdW = Wire(UInt(p.regIndexBits))
        rdW <== t.trace_rd.get
        probe.trace_rd.get <== rdW
        val rdWdataW = Wire(word)
        rdWdataW <== t.trace_rd_wdata.get
        probe.trace_rdWdata.get <== rdWdataW
        val rs1AddrW = Wire(UInt(5))
        rs1AddrW <== t.trace_rs1_addr.get
        probe.trace_rs1Addr.get <== rs1AddrW
        val rs1RdataW = Wire(word)
        rs1RdataW <== t.trace_rs1_rdata.get
        probe.trace_rs1Rdata.get <== rs1RdataW
        val rs2AddrW = Wire(UInt(5))
        rs2AddrW <== t.trace_rs2_addr.get
        probe.trace_rs2Addr.get <== rs2AddrW
        val rs2RdataW = Wire(word)
        rs2RdataW <== t.trace_rs2_rdata.get
        probe.trace_rs2Rdata.get <== rs2RdataW
        val memAddrW = Wire(word)
        memAddrW <== t.trace_mem_addr.get
        probe.trace_memAddr.get <== memAddrW
        val memRmaskW = Wire(UInt(4))
        memRmaskW <== t.trace_mem_rmask.get
        probe.trace_memRmask.get <== memRmaskW
        val memWmaskW = Wire(UInt(4))
        memWmaskW <== t.trace_mem_wmask.get
        probe.trace_memWmask.get <== memWmaskW
        val memRdataW = Wire(word)
        memRdataW <== t.trace_mem_rdata.get
        probe.trace_memRdata.get <== memRdataW
        val memWdataW = Wire(word)
        memWdataW <== t.trace_mem_wdata.get
        probe.trace_memWdata.get <== memWdataW
        val memFaultW = Wire(Bool())
        memFaultW <== t.trace_mem_fault.get
        probe.trace_memFault.get <== memFaultW
        val memFaultRmaskW = Wire(UInt(4))
        memFaultRmaskW <== t.trace_mem_fault_rmask.get
        probe.trace_memFaultRmask.get <== memFaultRmaskW
        val memFaultWmaskW = Wire(UInt(4))
        memFaultWmaskW <== t.trace_mem_fault_wmask.get
        probe.trace_memFaultWmask.get <== memFaultWmaskW
        val csrAddrW = Wire(UInt(12))
        csrAddrW <== t.trace_csr_addr.get
        probe.trace_csrAddr.get <== csrAddrW
        val csrRmaskW = Wire(word)
        csrRmaskW <== t.trace_csr_rmask.get
        probe.trace_csrRmask.get <== csrRmaskW
        val csrWmaskW = Wire(word)
        csrWmaskW <== t.trace_csr_wmask.get
        probe.trace_csrWmask.get <== csrWmaskW
        val csrRdataW = Wire(word)
        csrRdataW <== t.trace_csr_rdata.get
        probe.trace_csrRdata.get <== csrRdataW
        val csrWdataW = Wire(word)
        csrWdataW <== t.trace_csr_wdata.get
        probe.trace_csrWdata.get <== csrWdataW
        val trapW = Wire(Bool())
        trapW <== t.trace_trap.get
        probe.trace_trap.get <== trapW
        val trapCauseW = Wire(UInt(4))
        trapCauseW <== t.trace_trap_cause.get
        probe.trace_trapCause.get <== trapCauseW
        val mstatusW = Wire(word)
        mstatusW <== t.trace_mstatus.get
        probe.trace_mstatus.get <== mstatusW
        val mstatusPostCommitW = Wire(word)
        mstatusPostCommitW <== t.trace_mstatus_post_commit.get
        probe.trace_mstatusPostCommit.get <== mstatusPostCommitW
        val mstatusPreTrapW = Wire(word)
        mstatusPreTrapW <== t.trace_mstatus_pre_trap.get
        probe.trace_mstatusPreTrap.get <== mstatusPreTrapW
        val mieW = Wire(word)
        mieW <== t.trace_mie.get
        probe.trace_mie.get <== mieW
        val mtvecW = Wire(word)
        mtvecW <== t.trace_mtvec.get
        probe.trace_mtvec.get <== mtvecW
        val mepcW = Wire(word)
        mepcW <== t.trace_mepc.get
        probe.trace_mepc.get <== mepcW
        val mtvalW = Wire(word)
        mtvalW <== t.trace_mtval.get
        probe.trace_mtval.get <== mtvalW
        val mipW = Wire(word)
        mipW <== t.trace_mip.get
        probe.trace_mip.get <== mipW
        val mcauseW = Wire(word)
        mcauseW <== t.trace_mcause.get
        probe.trace_mcause.get <== mcauseW
        val irqPendingMaskW = Wire(word)
        irqPendingMaskW <== t.trace_irq_pending_mask.get
        probe.trace_irqPendingMask.get <== irqPendingMaskW

    // ---- debug: the module's nested bundle onto the hart's flat one ----
    io.debug.foreach { d =>
      val h = core.io.debug.get
      h.haltReq         := d.halt
      h.resumeReq       := d.resume
      h.resetReq        := d.reset
      h.haltOnResetReq  := d.haltOnReset
      h.abstractValid   := d.cmd.valid
      h.abstractCmdType := d.cmd.kind
      h.abstractWrite   := d.cmd.write
      h.abstractRegno   := d.cmd.regno
      h.abstractSize    := d.cmd.size
      h.abstractData    := d.cmd.data
      h.abstractAddress := d.cmd.address
      d.hart.halted     := h.hartHalted
      d.hart.running    := h.hartRunning
      d.hart.resumeAck  := h.hartResumeAck
      d.hart.resetAck   := h.hartResetAck
      d.hart.cmdDone    := h.abstractDone
      d.hart.cmdError   := h.abstractError
      d.hart.cmdRdata   := h.abstractRdata
    }

    // ---- AW / W: forwarded concurrently — W must not wait for the AW handshake (the AXI master rule; the peripheral
    // slaves hold AWREADY until they see WVALID). The write lane follows the live AW address until that handshake and
    // the latch afterwards; W flows whenever a lane is known. ----
    val awLane = RegInit(0.U(2))
    val awDone = RegInit(false.B)
    val wDone  = RegInit(false.B)

    io.mem.aw.valid      := core.io.axi.aw.valid & (!awDone)
    core.io.axi.aw.ready := io.mem.aw.ready & (!awDone)
    io.mem.aw.bits.id    := 0.U(p.idBits)
    io.mem.aw.bits.addr  := core.io.axi.aw.bits.addr.asBits.bits(p.addrBits - 1, 0).asUInt
    io.mem.aw.bits.len   := 0.U(8)
    io.mem.aw.bits.size  := 2.U(3)
    io.mem.aw.bits.burst := 1.U(2)

    val lane = Wire(UInt(2))
    lane := core.io.axi.aw.bits.addr.asBits.bits(3, 2).asUInt
    when(awDone) { lane := awLane }
    val laneKnown = awDone | core.io.axi.aw.valid

    val wData = Wire(UInt(p.dataBits))
    val wStrb = Wire(UInt(p.dataBits / 8))
    if p.dataBits == 32 then
      wData := core.io.axi.w.bits.data
      wStrb := core.io.axi.w.bits.strb
    else
      wData := (0.U(96).asBits ## core.io.axi.w.bits.data.asBits).asUInt
      wStrb := (0.U(12).asBits ## core.io.axi.w.bits.strb.asBits).asUInt
      for l <- 1 until 4 do
        when(lane === l.U(2)) {
          if l == 3 then
            wData := (core.io.axi.w.bits.data.asBits ## 0.U(96).asBits).asUInt
            wStrb := (core.io.axi.w.bits.strb.asBits ## 0.U(12).asBits).asUInt
          else
            wData := (0.U((3 - l) * 32).asBits ## core.io.axi.w.bits.data.asBits ## 0.U(l * 32).asBits).asUInt
            wStrb := (0.U((3 - l) * 4).asBits ## core.io.axi.w.bits.strb.asBits ## 0.U(l * 4).asBits).asUInt
        }

    io.mem.w.valid      := core.io.axi.w.valid & laneKnown & (!wDone)
    core.io.axi.w.ready := io.mem.w.ready & laneKnown & (!wDone)
    io.mem.w.bits.data  := wData
    io.mem.w.bits.strb  := wStrb
    io.mem.w.bits.last  := true.B

    val awHs = core.io.axi.aw.valid & io.mem.aw.ready & (!awDone)
    val wHs  = core.io.axi.w.valid & io.mem.w.ready & laneKnown & (!wDone)
    when(awHs) {
      awDone := true.B
      awLane := core.io.axi.aw.bits.addr.asBits.bits(3, 2).asUInt
    }
    when(wHs) { wDone := true.B }
    when((awDone | awHs) & (wDone | wHs)) {
      awDone := false.B
      wDone  := false.B
    }

    // ---- B / AR / R ----
    core.io.axi.b.valid     := io.mem.b.valid
    io.mem.b.ready          := core.io.axi.b.ready
    core.io.axi.b.bits.resp := io.mem.b.bits.resp

    val arLane = RegInit(0.U(2))
    io.mem.ar.valid      := core.io.axi.ar.valid
    core.io.axi.ar.ready := io.mem.ar.ready
    io.mem.ar.bits.id    := 0.U(p.idBits)
    io.mem.ar.bits.addr  := core.io.axi.ar.bits.addr.asBits.bits(p.addrBits - 1, 0).asUInt
    io.mem.ar.bits.len   := 0.U(8)
    io.mem.ar.bits.size  := 2.U(3)
    io.mem.ar.bits.burst := 1.U(2)
    when(core.io.axi.ar.valid & io.mem.ar.ready) {
      arLane := core.io.axi.ar.bits.addr.asBits.bits(3, 2).asUInt
    }

    val rWord = Wire(UInt(32))
    rWord := io.mem.r.bits.data.asBits.bits(31, 0).asUInt
    if p.dataBits == 128 then
      for lane <- 1 until 4 do
        when(arLane === lane.U(2)) {
          rWord := io.mem.r.bits.data.asBits.bits(lane * 32 + 31, lane * 32).asUInt
        }

    core.io.axi.r.valid     := io.mem.r.valid
    io.mem.r.ready          := core.io.axi.r.ready
    core.io.axi.r.bits.data := rWord
    core.io.axi.r.bits.resp := io.mem.r.bits.resp
