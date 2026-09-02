// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import upickle.default.ReadWriter

import java.lang.foreign.Arena

/** A real 128→32 width bridge in the spirit of rocket-chip's AXI4WidthWidget, for the traffic this SoC generates:
  * single-beat transfers of at most four bytes pass through — the addressed word lane is extracted on the way down and
  * re-placed on the way up — and anything wider answers SLVERR from the bridge itself. One outstanding transaction per
  * direction; ids pass through unchanged.
  */

case class WidthBridgeP(wide: AxiShape, narrow: AxiShape) extends Parameter derives ReadWriter:
  require(wide.dataBits == 128 && narrow.dataBits == 32, s"bridge is 128→32, got ${wide.dataBits}→${narrow.dataBits}")
  require(wide.idBits == narrow.idBits, s"bridge passes ids through, got ${wide.idBits} vs ${narrow.idBits}")
  require(wide.addrBits >= narrow.addrBits, s"narrow addresses embed in wide ones")

class WidthBridgePLayers(p: WidthBridgeP) extends LayerInterface(p):
  def layers = Seq.empty
class WidthBridgePProbe(p: WidthBridgeP)  extends DVBundle[WidthBridgeP, WidthBridgePLayers](p)
class WidthBridgePIO(p: WidthBridgeP)     extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val in  = Flipped(new AxiPortBundle(p.wide))
  val out = Aligned(new AxiPortBundle(p.narrow))

@generator
object WidthBridgeGen extends Generator[WidthBridgeP, WidthBridgePLayers, WidthBridgePIO, WidthBridgePProbe]:
  def architecture(p: WidthBridgeP) =
    val io           = summon[Interface[WidthBridgePIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    def narrowable(
      size: Referable[Bits],
      len:  Referable[Bits]
    )(
      using Arena,
      Context,
      Block
    ) =
      (size.asUInt <= 2.U(3)) & (len === 0.B(8))

    // ---- write path: 0 accept, 1 AW+W transfer, 3 B wait, 4 error absorb, 5 error answer. AW and W go out
    // concurrently — the peripheral may hold AWREADY until it sees WVALID, so W must not wait for the AW handshake. ----
    val wState  = RegInit(0.B(3))
    val wId     = RegInit(0.B(p.wide.idBits))
    val wLane   = RegInit(0.B(2))
    val wAddr   = RegInit(0.B(p.narrow.addrBits))
    val wSize   = RegInit(0.B(3))
    val wAwDone = RegInit(false.B)
    val wWDone  = RegInit(false.B)

    io.in.aw.ready := wState === 0.B(3)
    when(io.in.aw.valid & io.in.aw.ready) {
      wId   := io.in.aw.bits.id
      wLane := io.in.aw.bits.addr.bits(3, 2)
      wAddr := io.in.aw.bits.addr.bits(p.narrow.addrBits - 1, 0)
      wSize := io.in.aw.bits.size
      when(narrowable(io.in.aw.bits.size, io.in.aw.bits.len)) {
        wState := 1.B(3)
      }.otherwise {
        wState := 4.B(3)
      }
    }

    io.out.aw.valid      := (wState === 1.B(3)) & (!wAwDone)
    io.out.aw.bits.id    := wId
    io.out.aw.bits.addr  := wAddr
    io.out.aw.bits.len   := 0.B(8)
    io.out.aw.bits.size  := wSize
    io.out.aw.bits.burst := 1.B(2)

    // The wide beat carries the word in lane wLane: extract data and strobes.
    val wDataSel = Wire(Bits(32))
    val wStrbSel = Wire(Bits(4))
    wDataSel := io.in.w.bits.data.bits(31, 0)
    wStrbSel := io.in.w.bits.strb.bits(3, 0)
    for lane <- 1 until 4 do
      when(wLane === lane.B(2)) {
        wDataSel := io.in.w.bits.data.bits(lane * 32 + 31, lane * 32)
        wStrbSel := io.in.w.bits.strb.bits(lane * 4 + 3, lane * 4)
      }

    io.in.w.ready      := (wState === 1.B(3)) & io.out.w.ready & (!wWDone)
    io.out.w.valid     := (wState === 1.B(3)) & io.in.w.valid & (!wWDone)
    io.out.w.bits.data := wDataSel
    io.out.w.bits.strb := wStrbSel
    io.out.w.bits.last := true.B
    val wAwHs = (wState === 1.B(3)) & io.out.aw.ready & (!wAwDone)
    val wWHs  = (wState === 1.B(3)) & io.in.w.valid & io.out.w.ready & (!wWDone)
    when(wAwHs) { wAwDone := true.B }
    when(wWHs) { wWDone := true.B }
    when((wAwDone | wAwHs) & (wWDone | wWHs)) {
      wAwDone := false.B
      wWDone  := false.B
      wState  := 3.B(3)
    }

    io.out.b.ready := wState === 3.B(3)
    when((wState === 3.B(3)) & io.out.b.valid) { wState := 0.B(3) }

    // Error path: absorb the write beats, then answer SLVERR ourselves.
    when((wState === 4.B(3)) & io.in.w.valid & io.in.w.bits.last) { wState := 5.B(3) }

    io.in.b.valid     := ((wState === 3.B(3)) & io.out.b.valid) | (wState === 5.B(3))
    io.in.b.bits.id   := wId
    io.in.b.bits.resp := 0.B(2)
    when(wState === 3.B(3)) { io.in.b.bits.resp := io.out.b.bits.resp }
    when(wState === 5.B(3)) { io.in.b.bits.resp := 2.B(2) }
    when((wState === 5.B(3)) & io.in.b.ready) { wState := 0.B(3) }

    // in.w.ready must also drain the error path.
    when(wState === 4.B(3)) { io.in.w.ready := true.B }

    // ---- read path: RIDLE accept, RAR forward, RR return, RERR answer ----
    val rState = RegInit(0.B(2))
    val rId    = RegInit(0.B(p.wide.idBits))
    val rLane  = RegInit(0.B(2))
    val rAddr  = RegInit(0.B(p.narrow.addrBits))

    io.in.ar.ready := rState === 0.B(2)
    when(io.in.ar.valid & io.in.ar.ready) {
      rId   := io.in.ar.bits.id
      rLane := io.in.ar.bits.addr.bits(3, 2)
      rAddr := io.in.ar.bits.addr.bits(p.narrow.addrBits - 1, 0)
      when(narrowable(io.in.ar.bits.size, io.in.ar.bits.len)) {
        rState := 1.B(2)
      }.otherwise {
        rState := 3.B(2)
      }
    }

    io.out.ar.valid      := rState === 1.B(2)
    io.out.ar.bits.id    := rId
    io.out.ar.bits.addr  := rAddr
    io.out.ar.bits.len   := 0.B(8)
    io.out.ar.bits.size  := 2.B(3)
    io.out.ar.bits.burst := 1.B(2)
    when((rState === 1.B(2)) & io.out.ar.ready) { rState := 2.B(2) }

    // Place the narrow word back into its lane; the other lanes read zero.
    val rWide = Wire(Bits(128))
    rWide := (0.B(96) ## io.out.r.bits.data)
    for lane <- 1 until 4 do
      val hi = (3 - lane) * 32
      val lo = lane * 32
      when(rLane === lane.B(2)) {
        if lane == 3 then rWide := (io.out.r.bits.data ## 0.B(96))
        else rWide              := (0.B(hi) ## io.out.r.bits.data ## 0.B(lo))
      }

    io.out.r.ready    := (rState === 2.B(2)) & io.in.r.ready
    io.in.r.valid     := ((rState === 2.B(2)) & io.out.r.valid) | (rState === 3.B(2))
    io.in.r.bits.id   := rId
    io.in.r.bits.data := rWide
    io.in.r.bits.resp := 0.B(2)
    when(rState === 2.B(2)) { io.in.r.bits.resp := io.out.r.bits.resp }
    when(rState === 3.B(2)) {
      io.in.r.bits.resp := 2.B(2)
      io.in.r.bits.data := 0.B(128)
    }
    io.in.r.bits.last := true.B
    when((rState === 2.B(2)) & io.out.r.valid & io.in.r.ready) { rState := 0.B(2) }
    when((rState === 3.B(2)) & io.in.r.ready) { rState := 0.B(2) }
