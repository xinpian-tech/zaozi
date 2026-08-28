// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import upickle.default.ReadWriter

import java.lang.foreign.Arena

/** A real DRAM-backed AXI slave, rocket-chip's AXI4RAM extended with INCR bursts: a register-file backing store of
  * `2^wordsLog2` bus words, one outstanding transaction per direction, byte-lane strobes honoured on writes, `len`
  * beats streamed on reads. Addresses index the store modulo its size — the backing store models the array, not the
  * full decoded range.
  */

case class DramDeviceP(ranks: Int, wordsLog2: Int, addrBits: Int, dataBits: Int, idBits: Int) extends Parameter
    derives ReadWriter:
  require(ranks >= 1, s"dram needs at least one rank, got $ranks")
  require(dataBits == 128, s"dram is a 128-bit slave, got dataBits $dataBits")
  require(wordsLog2 >= 1 && wordsLog2 <= 16, s"dram wordsLog2 $wordsLog2 must be within 1..16")
  def shape: AxiShape = AxiShape(addrBits, dataBits, idBits)

class DramDevicePLayers(p: DramDeviceP) extends LayerInterface(p):
  def layers = Seq.empty
class DramDevicePProbe(p: DramDeviceP)  extends DVBundle[DramDeviceP, DramDevicePLayers](p)
class DramDevicePIO(p: DramDeviceP)     extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val in  = Flipped(new Axi4Bundle(p.shape))

@generator
object DramDeviceGen extends Generator[DramDeviceP, DramDevicePLayers, DramDevicePIO, DramDevicePProbe]:
  def architecture(p: DramDeviceP) =
    val io           = summon[Interface[DramDevicePIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    val lanes = p.dataBits / 8
    val store = Reg(Vec(1 << p.wordsLog2, UInt(p.dataBits)))

    def index(
      addr: Referable[UInt]
    )(
      using Arena,
      Context,
      Block
    ) =
      addr.asBits.bits(p.wordsLog2 + 3, 4).asUInt // 16-byte bus words

    def bump(
      idx: Referable[UInt]
    )(
      using Arena,
      Context,
      Block
    ) =
      (idx + 1.U(p.wordsLog2)).asBits.bits(p.wordsLog2 - 1, 0).asUInt

    // ---- write: AW, then len+1 W beats with byte strobes, then B ----
    val wActive  = RegInit(false.B)
    val wIdx     = RegInit(0.U(p.wordsLog2))
    val bPending = RegInit(false.B)
    val bId      = RegInit(0.U(p.idBits))

    io.in.aw.ready := (!wActive) & (!bPending)
    when(io.in.aw.valid & io.in.aw.ready) {
      wActive := true.B
      wIdx    := index(io.in.aw.bits.addr)
      bId     := io.in.aw.bits.id
    }

    io.in.w.ready := wActive
    when(wActive & io.in.w.valid) {
      def rep8(
        b: Referable[Bits]
      )(
        using Arena,
        Context,
        Block
      ) = b ## b ## b ## b ## b ## b ## b ## b
      val mask   = (0 until lanes).reverse
        .map(i => rep8(io.in.w.bits.strb.asBits.bits(i, i)))
        .reduce(_ ## _)
      val merged = (store(wIdx).asBits & (~mask)) | (io.in.w.bits.data.asBits & mask)
      store(wIdx) := merged.asUInt
      wIdx        := bump(wIdx)
      when(io.in.w.bits.last) {
        wActive  := false.B
        bPending := true.B
      }
    }

    io.in.b.valid     := bPending
    io.in.b.bits.id   := bId
    io.in.b.bits.resp := 0.U(2)
    when(bPending & io.in.b.ready) { bPending := false.B }

    // ---- read: AR, then len+1 R beats from the store ----
    val rActive = RegInit(false.B)
    val rIdx    = RegInit(0.U(p.wordsLog2))
    val rLeft   = RegInit(0.U(8))
    val rId     = RegInit(0.U(p.idBits))

    io.in.ar.ready := !rActive
    when(io.in.ar.valid & io.in.ar.ready) {
      rActive := true.B
      rIdx    := index(io.in.ar.bits.addr)
      rLeft   := io.in.ar.bits.len
      rId     := io.in.ar.bits.id
    }

    io.in.r.valid     := rActive
    io.in.r.bits.id   := rId
    io.in.r.bits.data := store(rIdx)
    io.in.r.bits.resp := 0.U(2)
    io.in.r.bits.last := rLeft === 0.U(8)
    when(rActive & io.in.r.ready) {
      rIdx := bump(rIdx)
      when(rLeft === 0.U(8)) {
        rActive := false.B
      }.otherwise {
        rLeft := (rLeft - 1.U(8)).asBits.bits(7, 0).asUInt
      }
    }
