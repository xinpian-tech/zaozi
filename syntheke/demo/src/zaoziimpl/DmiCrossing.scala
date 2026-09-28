package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.stdlib.queue.default.{AsyncQueue, AsyncQueueParameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter


case class DmiCrossingP(abits: Int, dataBits: Int, depth: Int) extends Parameter derives ReadWriter:
  require(abits >= 1 && abits <= 63, s"DMI address width $abits must be within 1..63")
  require(dataBits == 32, s"the DMI data field is 32 bits, got $dataBits")
  require(depth >= 4, s"queue depth $depth must be at least 4, the standard library's minimum")

  def reqBits:  Int = abits + dataBits + 2
  def respBits: Int = dataBits + 2

  def queueP(width: Int): AsyncQueueParameter = AsyncQueueParameter(
    width = width,
    depth = depth,
    pushAlmostEmptyLevel = 1,
    pushAlmostFullLevel = 1,
    popAlmostEmptyLevel = 1,
    popAlmostFullLevel = 1,
    stickyError = false,
    pushSync = 2,
    popSync = 2,
    asyncReset = true,
    resetMem = false
  )

class DmiCrossingPLayers(p: DmiCrossingP) extends LayerInterface(p):
  def layers = Seq(Layer("Verification"))
class DmiCrossingPProbe(p: DmiCrossingP)  extends DVBundle[DmiCrossingP, DmiCrossingPLayers](p)
class DmiCrossingPIO(p: DmiCrossingP)     extends HWBundle(p):
  val enqClk = Flipped(new ClockBundle)

  val deqClk = Flipped(new ClockBundle)
  val in     = Flipped(new DmiBundle(p.abits, p.dataBits))
  val out    = Aligned(new DmiBundle(p.abits, p.dataBits))

@generator
object DmiCrossingGen extends Generator[DmiCrossingP, DmiCrossingPLayers, DmiCrossingPIO, DmiCrossingPProbe]:
  def architecture(p: DmiCrossingP) =
    val io           = summon[Interface[DmiCrossingPIO]]
    given ClockScope = ClockScope.posedge(io.deqClk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.deqClk.reset)

    val resetN = (!io.deqClk.reset.asBool).asReset

    val req = AsyncQueue.instantiate(p.queueP(p.reqBits))
    req.io.resetN        := resetN
    req.io.push.clock    := io.enqClk.clock
    req.io.pop.clock     := io.deqClk.clock
    req.io.dataIn        := (io.in.req.bits.addr ## io.in.req.bits.data ## io.in.req.bits.op).asUInt
    req.io.push.requestN := !(io.in.req.valid & (!req.io.push.full))
    io.in.req.ready      := !req.io.push.full
    io.out.req.valid     := !req.io.pop.empty
    req.io.pop.requestN  := !(io.out.req.ready & (!req.io.pop.empty))
    io.out.req.bits.addr := req.io.dataOut.asBits.bits(p.reqBits - 1, p.dataBits + 2)
    io.out.req.bits.data := req.io.dataOut.asBits.bits(p.dataBits + 1, 2)
    io.out.req.bits.op   := req.io.dataOut.asBits.bits(1, 0)

    val resp = AsyncQueue.instantiate(p.queueP(p.respBits))
    resp.io.resetN        := resetN
    resp.io.push.clock    := io.deqClk.clock
    resp.io.pop.clock     := io.enqClk.clock
    resp.io.dataIn        := (io.out.resp.bits.data ## io.out.resp.bits.op).asUInt
    resp.io.push.requestN := !(io.out.resp.valid & (!resp.io.push.full))
    io.out.resp.ready     := !resp.io.push.full
    io.in.resp.valid      := !resp.io.pop.empty
    resp.io.pop.requestN  := !(io.in.resp.ready & (!resp.io.pop.empty))
    io.in.resp.bits.data  := resp.io.dataOut.asBits.bits(p.respBits - 1, 2)
    io.in.resp.bits.op    := resp.io.dataOut.asBits.bits(1, 0)
