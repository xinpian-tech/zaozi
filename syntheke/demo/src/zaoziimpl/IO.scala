package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

case class SerialIOP() extends Parameter derives ReadWriter
class SerialIOPLayers(p: SerialIOP) extends LayerInterface(p):
  def layers = Seq.empty
class SerialIOPProbe(p: SerialIOP) extends DVBundle[SerialIOP, SerialIOPLayers](p)
class SerialIOPIO(p: SerialIOP) extends HWBundle(p):
  val in = Flipped(new SerialBundle)
  val tx = Aligned(new IOBundle)
  val rx = Aligned(new IOBundle)

@generator
object SerialIOGen extends Generator[SerialIOP, SerialIOPLayers, SerialIOPIO, SerialIOPProbe]:
  def architecture(p: SerialIOP) =
    val io = summon[Interface[SerialIOPIO]]
    io.tx.inputEnable := false.B
    io.tx.outputValue := io.in.tx
    io.tx.outputEnable := true.B
    io.rx.inputEnable := true.B
    io.rx.outputValue := false.B
    io.rx.outputEnable := false.B
    io.in.rx := io.rx.inputValue

case class GpioIOP(width: Int) extends Parameter derives ReadWriter:
  require(width > 0, "GPIO IO adapter needs pins")
class GpioIOPLayers(p: GpioIOP) extends LayerInterface(p):
  def layers = Seq.empty
class GpioIOPProbe(p: GpioIOP) extends DVRecord[GpioIOP, GpioIOPLayers](p)
class GpioIOPIO(p: GpioIOP) extends HWRecord(p):
  val in = Flipped("in", new GpioPinsRecord(p.width))
  val pins = Vector.tabulate(p.width)(i => Aligned(s"pin$i", new IORecord))

@generator
object GpioIOGen extends Generator[GpioIOP, GpioIOPLayers, GpioIOPIO, GpioIOPProbe]:
  def architecture(p: GpioIOP) =
    val io = summon[Interface[GpioIOPIO]]
    val in = io.field[Record]("in")
    val pins = Vector.tabulate(p.width)(i => io.field[Record](s"pin$i"))
    pins.zipWithIndex.foreach { (pin, i) =>
      pin.field[Bool]("inputEnable") := true.B
      pin.field[Bool]("outputValue") := in.field[Bits]("out").bit(i)
      pin.field[Bool]("outputEnable") := in.field[Bits]("oe").bit(i)
    }
    in.field[Bits]("in") := pins.reverse.map(_.field[Bool]("inputValue").asBits: Referable[Bits]).reduce(_ ## _)

case class JtagIOP() extends Parameter derives ReadWriter
class JtagIOPLayers(p: JtagIOP) extends LayerInterface(p):
  def layers = Seq.empty
class JtagIOPProbe(p: JtagIOP) extends DVBundle[JtagIOP, JtagIOPLayers](p)
class JtagIOPIO(p: JtagIOP) extends HWBundle(p):
  val in = Flipped(new JtagBundle)
  val tms = Aligned(new IOBundle)
  val tdi = Aligned(new IOBundle)
  val trstN = Aligned(new IOBundle)
  val tdo = Aligned(new IOBundle)

@generator
object JtagIOGen extends Generator[JtagIOP, JtagIOPLayers, JtagIOPIO, JtagIOPProbe]:
  def architecture(p: JtagIOP) =
    val io = summon[Interface[JtagIOPIO]]
    Seq(io.tms, io.tdi, io.trstN).foreach { pin =>
      pin.inputEnable := true.B
      pin.outputValue := false.B
      pin.outputEnable := false.B
    }
    io.in.tms := io.tms.inputValue
    io.in.tdi := io.tdi.inputValue
    io.in.trstN := io.trstN.inputValue
    io.tdo.inputEnable := false.B
    io.tdo.outputValue := io.in.tdo
    io.tdo.outputEnable := true.B
