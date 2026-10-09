package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.stdlib.iomux.{IOMux, IOMuxOption, IOMuxParameter, IOMuxRoute, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

given ioRoutesTokens: mainargs.TokensReader.Simple[Vector[(String, IOMuxRoute)]] = jsonTokens("io-routes")

case class IOMuxP(
  base: Long,
  size: Long,
  shape: AxiShape,
  pinCount: Int,
  routes: Vector[(String, IOMuxRoute)]
) extends Parameter derives ReadWriter:
  require(shape.dataBits == 32, "IOMux AXI port requires 32-bit data")
  require(base >= 0 && base % 4 == 0 && size > 0 && BigInt(base) + size <= (BigInt(1) << shape.addrBits))
  require(routes.nonEmpty, "IOMux requires routes")
  require(routes.map(_._1).distinct.size == routes.size, "IOMux route names must be unique")
  val padNames = Vector.tabulate(pinCount)(i => s"pad$i")
  require(!routes.exists(r => (Set("clk", "in") ++ padNames)(r._1)), "IOMux route name conflicts with a port")
  val mux = IOMuxParameter(
    pinCount = pinCount,
    routes = routes.map(_._2),
    hsSlots = routes.map(_._2.slot).max + 1,
    dataWidth = shape.dataBits,
    addressWidth = shape.addrBits,
    option = IOMuxOption(rxOverride = routes.exists(_._2.tie.nonEmpty))
  )
  require(mux.windowBytes <= size, s"IOMux register window ${mux.windowBytes} exceeds $size bytes")

class IOMuxPLayers(p: IOMuxP) extends LayerInterface(p):
  def layers = Seq(p.mux.verification)
class IOMuxPProbe(p: IOMuxP) extends DVRecord[IOMuxP, IOMuxPLayers](p)
class IOMuxPIO(p: IOMuxP) extends HWRecord(p):
  val clk = Flipped("clk", new ClockRecord)
  val in = Flipped("in", new AxiPortBundle(p.shape))
  val pads = p.padNames.map(name => Aligned(name, new IORecord))
  val routes = p.routes.map((name, _) => Flipped(name, new IORecord))

@generator
object IOMuxGen extends Generator[IOMuxP, IOMuxPLayers, IOMuxPIO, IOMuxPProbe]:
  def architecture(p: IOMuxP) =
    val io = summon[Interface[IOMuxPIO]]
    val clk = io.field[Record]("clk")
    given ClockScope = ClockScope.posedge(clk.field[Clock]("clock"))
    given ResetScope = ResetScope.asyncActiveHigh(clk.field[Reset]("reset"))

    val mux = IOMux.instantiate(p.mux)
    mux.io.clock := clk.field[Clock]("clock")
    mux.io.resetN := (!clk.field[Reset]("reset").asBool).asReset
    val (req, rsp) = AxiRegMap(io.field[AxiPortBundle]("in"), p.shape)
    val address = (req.bits.index.asBits ## 0.B(2)).asUInt
    mux.io.req.valid := req.valid
    req.ready := mux.io.req.ready
    mux.io.req.bits.read := req.bits.read
    mux.io.req.bits.address := (address - p.base.U(p.shape.addrBits)).asBits.bits(p.shape.addrBits - 1, 0)
    mux.io.req.bits.data := req.bits.data
    mux.io.req.bits.mask := req.bits.mask
    rsp :<>= mux.io.rsp

    val inputs = p.routes.map((name, _) => io.field[Record](name))
    mux.io.inputEnable := inputs.reverse.map(_.field[Bool]("inputEnable").asBits: Referable[Bits]).reduce(_ ## _)
    mux.io.outputValue := inputs.reverse.map(_.field[Bool]("outputValue").asBits: Referable[Bits]).reduce(_ ## _)
    mux.io.outputEnable := inputs.reverse.map(_.field[Bool]("outputEnable").asBits: Referable[Bits]).reduce(_ ## _)
    inputs.zipWithIndex.foreach { (input, i) =>
      input.field[Bool]("inputValue") := mux.io.inputValue.bit(i)
    }

    val pads = p.padNames.map(name => io.field[Record](name))
    pads.zipWithIndex.foreach { (pad, i) =>
      pad.field[Bool]("inputEnable") := mux.io.padInputEnable.bit(i)
      pad.field[Bool]("outputValue") := mux.io.padOutputValue.bit(i)
      pad.field[Bool]("outputEnable") := mux.io.padOutputEnable.bit(i)
    }
    mux.io.padInputValue := pads.reverse.map(_.field[Bool]("inputValue").asBits: Referable[Bits]).reduce(_ ## _)
