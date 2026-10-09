package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.stdlib.prcm.{PRCM, PRCMDomainPort, PRCMParameter, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

given prcmTokens: mainargs.TokensReader.Simple[PRCMParameter] = jsonTokens("prcm")

/** zaozi PR #159's PRCM, unchanged and with its own `PRCMParameter`, behind an AXI register window at `base`. */
case class PrcmControllerP(base: Long, size: Long, shape: AxiShape, coldResetActiveLow: Boolean, prcm: PRCMParameter)
    extends Parameter derives ReadWriter:
  require(base >= 0 && base % 4 == 0 && BigInt(base) + size <= (BigInt(1) << shape.addrBits))
  require(prcm.windowBytes <= size, s"PRCM register window ${prcm.windowBytes} exceeds $size bytes")
  require(!prcm.domains.exists(d => Set("clk", "in")(d.name)), "a PRCM domain name conflicts with a port")

class PrcmControllerPLayers(p: PrcmControllerP) extends LayerInterface(p):
  def layers = Seq(p.prcm.verification)
class PrcmControllerPProbe(p: PrcmControllerP) extends DVRecord[PrcmControllerP, PrcmControllerPLayers](p)
class PrcmControllerPIO(p: PrcmControllerP) extends HWRecord(p):
  val clk     = Flipped("clk", new ClockRecord)
  val in      = Flipped("in", new AxiPortBundle(p.shape))
  val domains = p.prcm.orderedDomains.map(d => Aligned(d.name, new PRCMDomainPort))

@generator
object PrcmControllerGen extends Generator[PrcmControllerP, PrcmControllerPLayers, PrcmControllerPIO, PrcmControllerPProbe]:
  def architecture(p: PrcmControllerP) =
    val io    = summon[Interface[PrcmControllerPIO]]
    val clk   = io.field[Record]("clk")
    val reset = clk.field[Reset]("reset")
    given ClockScope = ClockScope.posedge(clk.field[Clock]("clock"))
    given ResetScope =
      if p.coldResetActiveLow then ResetScope.asyncActiveLow(reset) else ResetScope.asyncActiveHigh(reset)

    val prcm = PRCM.instantiate(p.prcm)
    prcm.io.clock           := clk.field[Clock]("clock")
    prcm.io.coldResetN      := (if p.coldResetActiveLow then reset else (!reset.asBool).asReset)
    prcm.io.managementReset := false.B

    val (req, rsp) = AxiRegMap(io.field[AxiPortBundle]("in"), p.shape)
    val offset     = ((req.bits.index.asBits ## 0.B(2)).asUInt - p.base.U(p.shape.addrBits)).asBits
    prcm.io.req.valid      := req.valid
    req.ready              := prcm.io.req.ready
    prcm.io.req.bits.read  := req.bits.read
    prcm.io.req.bits.index := offset.bits(p.prcm.indexWidth + 1, 2).asUInt
    prcm.io.req.bits.data  := req.bits.data
    prcm.io.req.bits.mask  := req.bits.mask
    rsp :<>= prcm.io.rsp

    // The PRCM orders its ports by domain name; each port here is named after its domain.
    p.prcm.orderedDomains.zipWithIndex.foreach { (domain, i) =>
      val port  = io.field[PRCMDomainPort](domain.name)
      val inner = prcm.io.domains(i)
      inner.powerGood       := port.powerGood
      inner.isolationActive := port.isolationActive
      inner.idle            := port.idle
      port.powerRequest     := inner.powerRequest
      port.isolationRequest := inner.isolationRequest
      port.quiesceRequest   := inner.quiesceRequest
      port.clock            := inner.clock
      port.resetN           := inner.resetN
    }
