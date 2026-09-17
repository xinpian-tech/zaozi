package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.stdlib.mmio.*
import upickle.default.ReadWriter


case class UartP(divisor: Int, base: Long, addrBits: Int, dataBits: Int, idBits: Int) extends Parameter derives ReadWriter:
  require(divisor >= 8, s"uart divisor $divisor: needs at least 8 clocks per bit")
  require(dataBits == 32, s"uart is a 32-bit single-beat slave, got dataBits $dataBits")
  require(base >= 0 && base % 4 == 0 && BigInt(base) + 12 < (BigInt(1) << addrBits))
  def shape: AxiShape = AxiShape(addrBits, dataBits, idBits)
  val transmit = RegField("transmit", 8).writeReadyValid
  val receive = RegField("receive", 8).readReadyValid
  val status = RegField("status", 2).readValue
  val baudDivisor = RegField("baudDivisor", 32).readValue
  val regMap = RegMapDefinition(addrBits - 2, 32, 1, true, Layer("Verification"), Seq(
    RegMapRegister(BigInt(base), Seq(transmit)),
    RegMapRegister(BigInt(base) + 4, Seq(receive)),
    RegMapRegister(BigInt(base) + 8, Seq(status)),
    RegMapRegister(BigInt(base) + 12, Seq(baudDivisor))
  ))

class UartPLayers(p: UartP) extends LayerInterface(p):
  def layers = Seq(p.regMap.assertionLayer)
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

    val txShift = RegInit(1023.B(10))
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

    val rxSync = RegInit(true.B)
    rxSync := io.serial.rx
    val rxShift = RegInit(0.B(8))
    val rxCnt   = RegInit(0.U(4))
    val rxBaud  = RegInit(0.U(divW))
    val rxBusy  = RegInit(false.B)
    val rxData  = RegInit(0.B(8))
    val rxValid = RegInit(false.B)

    val txStart = Wire(Bool())
    val txData = Wire(Bits(8))
    val rxRead = Wire(Bool())
    when(txStart) {
      txShift := 1.B(1) ## txData ## 0.B(1)
      txCnt := 10.U(4)
      txBaud := reload
    }
    when(rxRead) { rxValid := false.B }
    val (req, rsp) = AxiRegMap(io.in, p.shape)
    p.regMap(req, rsp)(
      p.transmit.write(!txBusy, txStart, txData),
      p.receive.read(rxRead, true.B, rxData),
      p.status.read(rxValid.asBits ## txBusy.asBits),
      p.baudDivisor.read(p.divisor.B(32))
    )

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
        when((rxCnt === 0.U(4)) & rxSync) { rxBusy := false.B }
        when((rxCnt >= 1.U(4)) & (rxCnt <= 8.U(4))) {
          rxShift := rxSync.asBits ## rxShift.bits(7, 1)
        }
        when(rxCnt === 9.U(4)) {
          rxBusy  := false.B
          rxData  := rxShift
          rxValid := true.B
        }
      }.otherwise {
        rxBaud := (rxBaud - 1.U(divW)).asBits.bits(divW - 1, 0).asUInt
      }
    }
