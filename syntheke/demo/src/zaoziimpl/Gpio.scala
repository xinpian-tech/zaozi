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

/** A real GPIO block, modeled on rocket-chip's periphery GPIO reduced to its registers: per-pin output value, output
  * enable and a two-stage synchronized input, behind a 32-bit single-beat AXI slave.
  *
  * Register map (word offsets):
  *   - 0x0 OUT (W): output values
  *   - 0x4 DIR (W): output enables, 1 drives the pad
  *   - 0x8 IN (R): synchronized pad inputs
  */

case class GpioDeviceP(width: Int, addrBits: Int, dataBits: Int, idBits: Int) extends Parameter derives ReadWriter:
  require(width >= 1 && width <= 32, s"gpio width $width must be within 1..32")
  require(dataBits == 32, s"gpio is a 32-bit single-beat slave, got dataBits $dataBits")
  def shape: AxiShape = AxiShape(addrBits, dataBits, idBits)

class GpioPinsBundle(width: Int) extends Bundle:
  val out = Aligned(UInt(width))
  val oe  = Aligned(UInt(width))
  val in  = Flipped(UInt(width))

/** The string-keyed twin, for a module whose ports are named by its parameter (the test harness). */
class GpioPinsRecord(width: Int) extends Record:
  val out = Aligned("out", UInt(width))
  val oe  = Aligned("oe", UInt(width))
  val in  = Flipped("in", UInt(width))

class GpioDevicePLayers(p: GpioDeviceP) extends LayerInterface(p):
  def layers = Seq.empty
class GpioDevicePProbe(p: GpioDeviceP)  extends DVBundle[GpioDeviceP, GpioDevicePLayers](p)
class GpioDevicePIO(p: GpioDeviceP)     extends HWBundle(p):
  val clk  = Flipped(new ClockBundle)
  val in   = Flipped(new Axi4Bundle(p.shape))
  val pins = Aligned(new GpioPinsBundle(p.width))

@generator
object GpioDeviceGen extends Generator[GpioDeviceP, GpioDevicePLayers, GpioDevicePIO, GpioDevicePProbe]:
  def architecture(p: GpioDeviceP) =
    val io           = summon[Interface[GpioDevicePIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    val outReg = RegInit(0.U(p.width))
    val dirReg = RegInit(0.U(p.width))
    val inMeta = RegInit(0.U(p.width))
    val inSync = RegInit(0.U(p.width))
    inMeta      := io.pins.in
    inSync      := inMeta
    io.pins.out := outReg
    io.pins.oe  := dirReg

    // ---- single-beat AXI slave ----
    val bPending = RegInit(false.B)
    val bId      = RegInit(0.U(p.idBits))
    val rPending = RegInit(false.B)
    val rId      = RegInit(0.U(p.idBits))
    val rData    = RegInit(0.U(32))

    io.in.aw.ready    := (!bPending) & io.in.w.valid
    io.in.w.ready     := (!bPending) & io.in.aw.valid
    io.in.b.valid     := bPending
    io.in.b.bits.id   := bId
    io.in.b.bits.resp := 0.U(2)
    when(bPending & io.in.b.ready) { bPending := false.B }
    when(io.in.aw.valid & io.in.w.valid & (!bPending)) {
      bPending := true.B
      bId      := io.in.aw.bits.id
      val wAddr = io.in.aw.bits.addr.asBits.bits(3, 2).asUInt
      val wVal  = io.in.w.bits.data.asBits.bits(p.width - 1, 0).asUInt
      when(wAddr === 0.U(2)) { outReg := wVal } // OUT
      when(wAddr === 1.U(2)) { dirReg := wVal } // DIR
    }

    io.in.ar.ready    := !rPending
    io.in.r.valid     := rPending
    io.in.r.bits.id   := rId
    io.in.r.bits.resp := 0.U(2)
    io.in.r.bits.data := rData
    io.in.r.bits.last := true.B
    when(rPending & io.in.r.ready) { rPending := false.B }
    when(io.in.ar.valid & (!rPending)) {
      rPending := true.B
      rId      := io.in.ar.bits.id
      val rAddr = io.in.ar.bits.addr.asBits.bits(3, 2).asUInt
      // The context parameters matter: ops must build in the caller's block, not the block where pad was defined.
      def pad(
        x: Referable[UInt]
      )(
        using Arena,
        Context,
        Block
      ) =
        if p.width == 32 then x.asBits.asUInt else (0.U(32 - p.width).asBits ## x.asBits).asUInt
      rData := 0.U(32)
      when(rAddr === 0.U(2)) { rData := pad(outReg) } // OUT
      when(rAddr === 1.U(2)) { rData := pad(dirReg) } // DIR
      when(rAddr === 2.U(2)) { rData := pad(inSync) } // IN
    }
