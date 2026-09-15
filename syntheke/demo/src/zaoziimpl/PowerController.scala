package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

case class PowerControllerP(base: Long, addrBits: Int, dataBits: Int, idBits: Int)
    extends Parameter derives ReadWriter:
  require(dataBits == 32, s"power controller requires 32-bit data, got $dataBits")
  require(base >= 0 && base % 4 == 0 && BigInt(base) + 8 < (BigInt(1) << addrBits), "power controller registers exceed address width")
  def shape: AxiShape = AxiShape(addrBits, dataBits, idBits)

class PowerControllerPLayers(p: PowerControllerP) extends LayerInterface(p):
  def layers = Seq.empty
class PowerControllerPProbe(p: PowerControllerP) extends DVBundle[PowerControllerP, PowerControllerPLayers](p)
class PowerControllerPIO(p: PowerControllerP) extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val in = Flipped(new AxiPortBundle(p.shape))
  val cpu0 = Aligned(new PowerControlBundle)
  val cpu1 = Aligned(new PowerControlBundle)

@generator
object PowerControllerGen extends Generator[PowerControllerP, PowerControllerPLayers, PowerControllerPIO, PowerControllerPProbe]:
  def architecture(p: PowerControllerP) =
    val io = summon[Interface[PowerControllerPIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    val control = RegInit(3.B(2))
    io.cpu0.requestOn := control.bits(0, 0).asBool
    io.cpu1.requestOn := control.bits(1, 1).asBool

    val wActive = RegInit(false.B)
    val wError = RegInit(false.B)
    val bPending = RegInit(false.B)
    val bId = RegInit(0.B(p.idBits))
    val bError = RegInit(false.B)

    io.in.aw.ready := (!wActive) & (!bPending)
    io.in.w.ready := wActive
    io.in.b.valid := bPending
    io.in.b.bits.id := bId
    io.in.b.bits.resp := bError ? (2.B(2), 0.B(2))

    when(io.in.aw.valid & io.in.aw.ready) {
      wActive := true.B
      bId := io.in.aw.bits.id
      wError := !(
        (io.in.aw.bits.addr === p.base.B(p.addrBits)) &
        (io.in.aw.bits.len === 0.B(8)) &
        (io.in.aw.bits.size === 2.B(3)) &
        ((io.in.aw.bits.burst === 0.B(2)) | (io.in.aw.bits.burst === 1.B(2)))
      )
    }
    when(io.in.w.valid & io.in.w.ready) {
      when(io.in.w.bits.last) {
        wActive := false.B
        bPending := true.B
        bError := wError
        when((!wError) & io.in.w.bits.strb.bits(0, 0).asBool) {
          control := io.in.w.bits.data.bits(1, 0)
        }
      }.otherwise {
        wError := true.B
      }
    }
    when(io.in.b.valid & io.in.b.ready) {
      bPending := false.B
    }

    val rPending = RegInit(false.B)
    val rId = RegInit(0.B(p.idBits))
    val rData = RegInit(0.B(32))
    val rError = RegInit(false.B)
    val rRemaining = RegInit(0.U(8))

    io.in.ar.ready := !rPending
    io.in.r.valid := rPending
    io.in.r.bits.id := rId
    io.in.r.bits.data := rData
    io.in.r.bits.resp := rError ? (2.B(2), 0.B(2))
    io.in.r.bits.last := rRemaining === 0.U(8)

    when(io.in.ar.valid & io.in.ar.ready) {
      val readControl = io.in.ar.bits.addr === p.base.B(p.addrBits)
      val readCpu0 = io.in.ar.bits.addr === (p.base + 4).B(p.addrBits)
      val readCpu1 = io.in.ar.bits.addr === (p.base + 8).B(p.addrBits)
      val valid = (readControl | readCpu0 | readCpu1) &
        (io.in.ar.bits.len === 0.B(8)) &
        (io.in.ar.bits.size === 2.B(3)) &
        ((io.in.ar.bits.burst === 0.B(2)) | (io.in.ar.bits.burst === 1.B(2)))
      rPending := true.B
      rId := io.in.ar.bits.id
      rRemaining := io.in.ar.bits.len.asUInt
      rError := !valid
      rData := 0.B(32)
      when(valid & readControl) {
        rData := 0.B(30) ## control
      }
      when(valid & readCpu0) {
        rData := 0.B(28) ## io.cpu0.isolated.asBits ## io.cpu0.powerGood.asBits ## io.cpu0.busy.asBits ## io.cpu0.on.asBits
      }
      when(valid & readCpu1) {
        rData := 0.B(28) ## io.cpu1.isolated.asBits ## io.cpu1.powerGood.asBits ## io.cpu1.busy.asBits ## io.cpu1.on.asBits
      }
    }
    when(io.in.r.valid & io.in.r.ready) {
      when(rRemaining === 0.U(8)) {
        rPending := false.B
      }.otherwise {
        rRemaining := (rRemaining - 1.U(8)).asBits.bits(7, 0).asUInt
      }
    }
