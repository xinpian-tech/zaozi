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

/** A real boot ROM, rocket-chip's AXI4ROM: the image is combinational content behind a single-beat read port — each bus
  * word one 128-bit literal in a mux chain. `image` lists 32-bit words, little-endian lanes; a read returns the full
  * bus word and the master extracts its lane. A ROM has no write side: writes are answered with SLVERR.
  */

given romImageTokens: mainargs.TokensReader.Simple[Vector[Long]] = jsonTokens("rom-image")

case class BootRomP(image: Vector[Long], addrBits: Int, dataBits: Int, idBits: Int) extends Parameter
    derives ReadWriter:
  require(dataBits == 128, s"the boot rom is a 128-bit slave, got dataBits $dataBits")
  require(image.nonEmpty, "the boot rom needs an image")
  require(image.forall(w => w >= 0 && w <= 0xffffffffL), "boot rom image words are 32-bit")
  def shape: AxiShape = AxiShape(addrBits, dataBits, idBits)

class BootRomPLayers(p: BootRomP) extends LayerInterface(p):
  def layers = Seq.empty
class BootRomPProbe(p: BootRomP)  extends DVBundle[BootRomP, BootRomPLayers](p)
class BootRomPIO(p: BootRomP)     extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val in  = Flipped(new Axi4Bundle(p.shape))

@generator
object BootRomGen extends Generator[BootRomP, BootRomPLayers, BootRomPIO, BootRomPProbe]:
  def architecture(p: BootRomP) =
    val io           = summon[Interface[BootRomPIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    val beats = p.image.grouped(4).map(_.padTo(4, 0L)).toVector
    val idxW  = math.max(1, 32 - Integer.numberOfLeadingZeros(beats.length - 1))

    def beat(
      i: Int
    )(
      using Arena,
      Context,
      Block
    ) =
      val ws = beats(i)
      (ws(3).U(32).asBits ## ws(2).U(32).asBits ## ws(1).U(32).asBits ## ws(0).U(32).asBits).asUInt

    // ---- read: one outstanding, one beat, the addressed bus word ----
    val rPending = RegInit(false.B)
    val rId      = RegInit(0.U(p.idBits))
    val rIdx     = RegInit(0.U(idxW))

    io.in.ar.ready    := !rPending
    when(io.in.ar.valid & io.in.ar.ready) {
      rPending := true.B
      rId      := io.in.ar.bits.id
      rIdx     := io.in.ar.bits.addr.asBits.bits(idxW + 3, 4).asUInt // 16-byte bus words
    }
    val rData = Wire(UInt(p.dataBits))
    rData             := beat(0)
    for i <- 1 until beats.length do when(rIdx === i.U(idxW)) { rData := beat(i) }
    io.in.r.valid     := rPending
    io.in.r.bits.id   := rId
    io.in.r.bits.data := rData
    io.in.r.bits.resp := 0.U(2)
    io.in.r.bits.last := true.B
    when(rPending & io.in.r.ready) { rPending := false.B }

    // ---- write: a ROM answers with SLVERR ----
    val bPending = RegInit(false.B)
    val bId      = RegInit(0.U(p.idBits))
    io.in.aw.ready    := (!bPending) & io.in.w.valid
    io.in.w.ready     := (!bPending) & io.in.aw.valid
    io.in.b.valid     := bPending
    io.in.b.bits.id   := bId
    io.in.b.bits.resp := 2.U(2)
    when(io.in.aw.valid & io.in.w.valid & (!bPending)) {
      bPending := true.B
      bId      := io.in.aw.bits.id
    }
    when(bPending & io.in.b.ready) { bPending := false.B }
