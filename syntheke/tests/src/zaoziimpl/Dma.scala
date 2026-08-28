// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

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

    // States: 0 issue AW, 1 issue W, 2 wait B.
    val state = RegInit(0.U(2))

    io.mem.aw.valid      := state === 0.U(2)
    io.mem.aw.bits.id    := 0.U(p.idBits)
    io.mem.aw.bits.addr  := (((p.targetBase >> offW).toInt).U(p.addrBits - offW).asBits ## offset.asBits).asUInt
    io.mem.aw.bits.len   := 0.U(8)
    io.mem.aw.bits.size  := 4.U(3) // 16 bytes per beat
    io.mem.aw.bits.burst := 1.U(2) // INCR
    when((state === 0.U(2)) & io.mem.aw.ready) { state := 1.U(2) }

    io.mem.w.valid     := state === 1.U(2)
    io.mem.w.bits.data := (0.U(96).asBits ## beat.asBits).asUInt
    io.mem.w.bits.strb := 65535.U(16)
    io.mem.w.bits.last := true.B
    when((state === 1.U(2)) & io.mem.w.ready) { state := 2.U(2) }

    io.mem.b.ready := state === 2.U(2)
    when((state === 2.U(2)) & io.mem.b.valid) {
      state  := 0.U(2)
      offset := (offset + 16.U(offW)).asBits.bits(offW - 1, 0).asUInt
      beat   := (beat + 1.U(32)).asBits.bits(31, 0).asUInt
    }

    // The read side is unused: never request, always accept.
    io.mem.ar.valid      := false.B
    io.mem.ar.bits.id    := 0.U(p.idBits)
    io.mem.ar.bits.addr  := 0.U(p.addrBits)
    io.mem.ar.bits.len   := 0.U(8)
    io.mem.ar.bits.size  := 0.U(3)
    io.mem.ar.bits.burst := 1.U(2)
    io.mem.r.ready       := true.B
