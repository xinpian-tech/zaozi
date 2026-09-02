// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

/** A real DMA engine: a self-running stride writer. It walks a `2^windowLog2`-byte window from `targetBase` in
  * single-beat bus-word writes — id 0, one transaction in flight — stamping an incrementing beat counter into the low
  * word, and wraps forever. The read channels are tied off.
  */

case class DmaDeviceP(targetBase: Long, windowLog2: Int, addrBits: Int, dataBits: Int, idBits: Int) extends Parameter
    derives ReadWriter:
  require(dataBits == 128, s"dma issues 128-bit beats, got dataBits $dataBits")
  require(windowLog2 >= 4 && windowLog2 <= 30, s"dma windowLog2 $windowLog2 must be within 4..30")
  require(targetBase >= 0 && (targetBase & ((1L << windowLog2) - 1)) == 0, s"dma window must be aligned to its size")

class DmaDevicePLayers(p: DmaDeviceP) extends LayerInterface(p):
  def layers = Seq.empty
class DmaDevicePProbe(p: DmaDeviceP)  extends DVBundle[DmaDeviceP, DmaDevicePLayers](p)
class DmaDevicePIO(p: DmaDeviceP)     extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val mem = Aligned(new Axi4Bundle(AxiShape(p.addrBits, p.dataBits, p.idBits)))

@generator
object DmaDeviceGen extends Generator[DmaDeviceP, DmaDevicePLayers, DmaDevicePIO, DmaDevicePProbe]:
  def architecture(p: DmaDeviceP) =
    val io           = summon[Interface[DmaDevicePIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    val offW   = p.windowLog2
    val offset = RegInit(0.U(offW)) // byte offset inside the window, bus-word aligned
    val beat   = RegInit(0.U(32))

    // States: 0 issue AW and W together (W must not wait for the AW handshake — the AXI master rule), 2 wait B.
    val state  = RegInit(0.B(2))
    val awDone = RegInit(false.B)
    val wDone  = RegInit(false.B)

    io.mem.aw.valid      := (state === 0.B(2)) & (!awDone)
    io.mem.aw.bits.id    := 0.B(p.idBits)
    io.mem.aw.bits.addr  := ((p.targetBase >> offW).toInt).B(p.addrBits - offW) ## offset.asBits
    io.mem.aw.bits.len   := 0.B(8)
    io.mem.aw.bits.size  := 4.B(3) // 16 bytes per beat
    io.mem.aw.bits.burst := 1.B(2) // INCR

    io.mem.w.valid     := (state === 0.B(2)) & (!wDone)
    io.mem.w.bits.data := 0.B(96) ## beat.asBits
    io.mem.w.bits.strb := 65535.B(16)
    io.mem.w.bits.last := true.B

    val awHs = (state === 0.B(2)) & io.mem.aw.ready & (!awDone)
    val wHs  = (state === 0.B(2)) & io.mem.w.ready & (!wDone)
    when(awHs) { awDone := true.B }
    when(wHs) { wDone := true.B }
    when((awDone | awHs) & (wDone | wHs)) {
      awDone := false.B
      wDone  := false.B
      state  := 2.B(2)
    }

    io.mem.b.ready := state === 2.B(2)
    when((state === 2.B(2)) & io.mem.b.valid) {
      state  := 0.B(2)
      offset := (offset + 16.U(offW)).asBits.bits(offW - 1, 0).asUInt
      beat   := (beat + 1.U(32)).asBits.bits(31, 0).asUInt
    }

    // The read side is unused: never request, always accept.
    io.mem.ar.valid      := false.B
    io.mem.ar.bits.id    := 0.B(p.idBits)
    io.mem.ar.bits.addr  := 0.B(p.addrBits)
    io.mem.ar.bits.len   := 0.B(8)
    io.mem.ar.bits.size  := 0.B(3)
    io.mem.ar.bits.burst := 1.B(2)
    io.mem.r.ready       := true.B
