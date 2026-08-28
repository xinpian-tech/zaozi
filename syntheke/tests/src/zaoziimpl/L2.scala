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

/** A real L2-slot adapter with rocket-chip AXI4Buffer semantics: a one-deep register slice on every channel — AW, W, AR
  * forward, B, R backward. One entry per channel, half rate, full decoupling of the two sides.
  *
  * The negotiated contract reserves an extra writeback id range (`l2.wb`) after the upstream id space; this
  * implementation never issues it, so downstream ids are a superset of upstream ids: forward ids zero-extend, backward
  * ids truncate losslessly.
  */

case class L2DeviceP(capacityKiB: Int, up: AxiShape, down: AxiShape) extends Parameter derives ReadWriter:
  require(capacityKiB >= 1, s"l2 capacity $capacityKiB KiB must be positive")
  require(up.dataBits == down.dataBits, s"l2 passes data through, got ${up.dataBits} vs ${down.dataBits}")
  require(up.addrBits == down.addrBits, s"l2 passes addresses through, got ${up.addrBits} vs ${down.addrBits}")
  require(down.idBits >= up.idBits, s"downstream ids include the upstream ids, got ${down.idBits} < ${up.idBits}")

class L2DevicePLayers(p: L2DeviceP) extends LayerInterface(p):
  def layers = Seq.empty
class L2DevicePProbe(p: L2DeviceP)  extends DVBundle[L2DeviceP, L2DevicePLayers](p)
class L2DevicePIO(p: L2DeviceP)     extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val in  = Flipped(new Axi4Bundle(p.up))
  val out = Aligned(new Axi4Bundle(p.down))

@generator
object L2DeviceGen extends Generator[L2DeviceP, L2DevicePLayers, L2DevicePIO, L2DevicePProbe]:
  def architecture(p: L2DeviceP) =
    val io           = summon[Interface[L2DevicePIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    // The context parameters matter: ops must build in the caller's block, not the block where padId was defined.
    def padId(
      x: Referable[UInt]
    )(
      using Arena,
      Context,
      Block
    ) =
      if p.down.idBits == p.up.idBits then x.asBits.asUInt
      else (0.U(p.down.idBits - p.up.idBits).asBits ## x.asBits).asUInt

    // ---- AW forward slice ----
    val awFull = RegInit(false.B)
    val awReg  = Reg(new AxiAxBundle(p.down))
    io.in.aw.ready       := !awFull
    when(io.in.aw.valid & (!awFull)) {
      awFull      := true.B
      awReg.id    := padId(io.in.aw.bits.id)
      awReg.addr  := io.in.aw.bits.addr
      awReg.len   := io.in.aw.bits.len
      awReg.size  := io.in.aw.bits.size
      awReg.burst := io.in.aw.bits.burst
    }
    io.out.aw.valid      := awFull
    io.out.aw.bits.id    := awReg.id
    io.out.aw.bits.addr  := awReg.addr
    io.out.aw.bits.len   := awReg.len
    io.out.aw.bits.size  := awReg.size
    io.out.aw.bits.burst := awReg.burst
    when(awFull & io.out.aw.ready) { awFull := false.B }

    // ---- W forward slice ----
    val wFull = RegInit(false.B)
    val wReg  = Reg(new AxiWBundle(p.down))
    io.in.w.ready      := !wFull
    when(io.in.w.valid & (!wFull)) {
      wFull     := true.B
      wReg.data := io.in.w.bits.data
      wReg.strb := io.in.w.bits.strb
      wReg.last := io.in.w.bits.last
    }
    io.out.w.valid     := wFull
    io.out.w.bits.data := wReg.data
    io.out.w.bits.strb := wReg.strb
    io.out.w.bits.last := wReg.last
    when(wFull & io.out.w.ready) { wFull := false.B }

    // ---- AR forward slice ----
    val arFull = RegInit(false.B)
    val arReg  = Reg(new AxiAxBundle(p.down))
    io.in.ar.ready       := !arFull
    when(io.in.ar.valid & (!arFull)) {
      arFull      := true.B
      arReg.id    := padId(io.in.ar.bits.id)
      arReg.addr  := io.in.ar.bits.addr
      arReg.len   := io.in.ar.bits.len
      arReg.size  := io.in.ar.bits.size
      arReg.burst := io.in.ar.bits.burst
    }
    io.out.ar.valid      := arFull
    io.out.ar.bits.id    := arReg.id
    io.out.ar.bits.addr  := arReg.addr
    io.out.ar.bits.len   := arReg.len
    io.out.ar.bits.size  := arReg.size
    io.out.ar.bits.burst := arReg.burst
    when(arFull & io.out.ar.ready) { arFull := false.B }

    // ---- B backward slice (upstream ids only: the wb range is never issued) ----
    val bFull = RegInit(false.B)
    val bReg  = Reg(new AxiBBundle(p.up))
    io.out.b.ready    := !bFull
    when(io.out.b.valid & (!bFull)) {
      bFull     := true.B
      bReg.id   := io.out.b.bits.id.asBits.bits(p.up.idBits - 1, 0).asUInt
      bReg.resp := io.out.b.bits.resp
    }
    io.in.b.valid     := bFull
    io.in.b.bits.id   := bReg.id
    io.in.b.bits.resp := bReg.resp
    when(bFull & io.in.b.ready) { bFull := false.B }

    // ---- R backward slice ----
    val rFull = RegInit(false.B)
    val rReg  = Reg(new AxiRBundle(p.up))
    io.out.r.ready    := !rFull
    when(io.out.r.valid & (!rFull)) {
      rFull     := true.B
      rReg.id   := io.out.r.bits.id.asBits.bits(p.up.idBits - 1, 0).asUInt
      rReg.data := io.out.r.bits.data
      rReg.resp := io.out.r.bits.resp
      rReg.last := io.out.r.bits.last
    }
    io.in.r.valid     := rFull
    io.in.r.bits.id   := rReg.id
    io.in.r.bits.data := rReg.data
    io.in.r.bits.resp := rReg.resp
    io.in.r.bits.last := rReg.last
    when(rFull & io.in.r.ready) { rFull := false.B }
