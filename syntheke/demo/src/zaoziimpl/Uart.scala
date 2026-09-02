// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

/** A real UART device as a plain zaozi generator — zaozi API only, no syntheke construct; `UartSpec.scala` (in the
  * circt test module) wraps it onto the negotiation graph and tests it end to end.
  *
  * Register map (word offsets on a 32-bit single-beat AXI slave — the negotiated contract `beatBytes = 4`,
  * `maxTransfer = 4` means one beat per burst):
  *   - 0x0 TXDATA (W): low byte starts a transmission when idle
  *   - 0x4 RXDATA (R): last received byte; reading clears the valid flag
  *   - 0x8 STATUS (R): bit0 txBusy, bit1 rxValid
  *   - 0xC DIV (R): the baud divisor this instance was built with
  *
  * Serial format 8N1, LSB first; `divisor` clocks per bit, receive sampled at mid-bit.
  */

case class UartP(divisor: Int, addrBits: Int, dataBits: Int, idBits: Int) extends Parameter derives ReadWriter:
  require(divisor >= 8, s"uart divisor $divisor: needs at least 8 clocks per bit")
  require(dataBits == 32, s"uart is a 32-bit single-beat slave, got dataBits $dataBits")
  def shape: AxiShape = AxiShape(addrBits, dataBits, idBits)

class UartPLayers(p: UartP) extends LayerInterface(p):
  def layers = Seq.empty
class UartPProbe(p: UartP)  extends DVBundle[UartP, UartPLayers](p)
class UartPIO(p: UartP)     extends HWBundle(p):
  val clk    = Flipped(new ClockBundle)
  val in     = Flipped(new AxiPortBundle(p.shape))
  val serial = Aligned(new SerialBundle)

@generator
object UartGen extends Generator[UartP, UartPLayers, UartPIO, UartPProbe]:
  def architecture(p: UartP) =
    val io           = summon[Interface[UartPIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    val divW   = math.max(1, 32 - Integer.numberOfLeadingZeros(p.divisor - 1))
    val reload = (p.divisor - 1).U(divW)

    // ---- transmit engine ----
    val txShift = RegInit(1023.B(10)) // {stop, data, start}; all ones = idle line
    val txCnt   = RegInit(0.U(4))
    val txBaud  = RegInit(0.U(divW))
    val txBusy  = txCnt =/= 0.U(4)
    io.serial.tx := txShift.bits(0, 0).asBool
    when(txBusy) {
      when(txBaud === 0.U(divW)) {
        txBaud  := reload
        txCnt   := (txCnt - 1.U(4)).asBits.bits(3, 0).asUInt
        txShift := 1.B(1) ## txShift.bits(9, 1)
      }.otherwise {
        txBaud := (txBaud - 1.U(divW)).asBits.bits(divW - 1, 0).asUInt
      }
    }

    // ---- receive engine state (the engine itself is below the register file: completion beats a read-clear) ----
    val rxSync = RegInit(true.B)
    rxSync := io.serial.rx
    val rxShift = RegInit(0.B(8))
    val rxCnt   = RegInit(0.U(4))
    val rxBaud  = RegInit(0.U(divW))
    val rxBusy  = RegInit(false.B)
    val rxData  = RegInit(0.B(8))
    val rxValid = RegInit(false.B)

    // ---- single-beat AXI slave ----
    val bPending = RegInit(false.B)
    val bId      = RegInit(0.B(p.idBits))
    val rPending = RegInit(false.B)
    val rId      = RegInit(0.B(p.idBits))
    val rData    = RegInit(0.B(32))

    io.in.aw.ready    := (!bPending) & io.in.w.valid
    io.in.w.ready     := (!bPending) & io.in.aw.valid
    io.in.b.valid     := bPending
    io.in.b.bits.id   := bId
    io.in.b.bits.resp := 0.B(2)
    val doWrite = io.in.aw.valid & io.in.w.valid & (!bPending)
    when(bPending & io.in.b.ready) { bPending := false.B }
    when(doWrite) {
      bPending := true.B
      bId      := io.in.aw.bits.id
      val wAddr = io.in.aw.bits.addr.bits(3, 2)
      when((wAddr === 0.B(2)) & (!txBusy)) { // TXDATA
        txShift := 1.B(1) ## io.in.w.bits.data.bits(7, 0) ## 0.B(1)
        txCnt   := 10.U(4)
        txBaud  := reload
      }
    }

    io.in.ar.ready    := !rPending
    io.in.r.valid     := rPending
    io.in.r.bits.id   := rId
    io.in.r.bits.resp := 0.B(2)
    io.in.r.bits.data := rData
    io.in.r.bits.last := true.B
    when(rPending & io.in.r.ready) { rPending := false.B }
    when(io.in.ar.valid & (!rPending)) {
      rPending := true.B
      rId      := io.in.ar.bits.id
      val rAddr = io.in.ar.bits.addr.bits(3, 2)
      rData := 0.B(32)
      when(rAddr === 1.B(2)) { // RXDATA
        rData   := 0.B(24) ## rxData
        rxValid := false.B
      }
      when(rAddr === 2.B(2)) { // STATUS
        rData := 0.B(30) ## rxValid.asBits ## txBusy.asBits
      }
      when(rAddr === 3.B(2)) { // DIV
        rData := p.divisor.B(32)
      }
    }

    // Receive: start on the falling edge, sample at mid-bit, LSB first; a completion beats a same-cycle read-clear.
    when(!rxBusy) {
      when(!rxSync) {
        rxBusy := true.B
        rxCnt  := 0.U(4)
        rxBaud := (p.divisor / 2).U(divW)
      }
    }.otherwise {
      when(rxBaud === 0.U(divW)) {
        rxBaud := reload
        rxCnt  := (rxCnt + 1.U(4)).asBits.bits(3, 0).asUInt
        when((rxCnt === 0.U(4)) & rxSync) { rxBusy := false.B } // start bit was a glitch
        when((rxCnt >= 1.U(4)) & (rxCnt <= 8.U(4))) {
          rxShift := rxSync.asBits ## rxShift.bits(7, 1)
        }
        when(rxCnt === 9.U(4)) { // stop bit: byte complete
          rxBusy  := false.B
          rxData  := rxShift
          rxValid := true.B
        }
      }.otherwise {
        rxBaud := (rxBaud - 1.U(divW)).asBits.bits(divW - 1, 0).asUInt
      }
    }
