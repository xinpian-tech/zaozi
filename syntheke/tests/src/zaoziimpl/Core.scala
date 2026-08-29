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

case class CoreDeviceP(resetPc: Int, addrBits: Int, dataBits: Int, idBits: Int, enableDebug: Boolean) extends Parameter
    derives ReadWriter:
  require(dataBits == 32 || dataBits == 128, s"the core shim rides a 32- or 128-bit fabric, got dataBits $dataBits")
  require(addrBits >= 1 && addrBits <= 32, s"the core addresses at most a 32-bit space, got addrBits $addrBits")
  require((resetPc & 0x3) == 0 && resetPc >= 0, s"resetPc 0x${resetPc.toHexString} must be 32-bit aligned")
  val xlen: Int = 32

class CoreDevicePLayers(p: CoreDeviceP) extends LayerInterface(p):
  def layers = Seq.empty
class CoreDevicePProbe(p: CoreDeviceP)  extends DVBundle[CoreDeviceP, CoreDevicePLayers](p)
class CoreDevicePIO(p: CoreDeviceP)     extends HWBundle(p):
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
      DitDah32Parameter(resetVector = p.resetPc, enableDebug = p.enableDebug)
    )
    core.io.clock        := io.clk.clock
    core.io.reset        := io.clk.reset
    core.io.irq.software := false.B
    core.io.irq.timer    := false.B
    core.io.irq.external := false.B

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
