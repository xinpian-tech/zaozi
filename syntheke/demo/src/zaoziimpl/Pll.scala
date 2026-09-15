package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter


case class PllP(refHz: Int, outHz: Int, mult: Int, div: Int, taps: Vector[String]) extends Parameter derives ReadWriter:
  require(refHz > 0, s"reference frequency $refHz must be positive")
  require(outHz > 0, s"output frequency $outHz must be positive")
  require(mult > 0 && div > 0, s"the loop ratio $mult/$div must be positive")
  require(
    refHz.toLong * mult == outHz.toLong * div,
    s"$refHz Hz * $mult / $div is not $outHz Hz"
  )
  require(taps.nonEmpty, "a PLL drives at least one clock tap")
  require(taps.distinct.sizeIs == taps.size, s"clock taps must be uniquely named: ${taps.mkString(", ")}")
  def analogP: PllAnalogP = PllAnalogP(refHz, outHz, mult, div)

class PllPLayers(p: PllP) extends LayerInterface(p):
  def layers = Seq.empty
class PllPProbe(p: PllP)  extends DVRecord[PllP, PllPLayers](p)
class PllPIO(p: PllP)     extends HWRecord(p):
  val ref  = Flipped("ref", new ClockRecord)
  val taps = p.taps.map(n => Aligned(n, new ClockRecord))

@generator
object PllGen extends Generator[PllP, PllPLayers, PllPIO, PllPProbe]:
  def architecture(p: PllP) =
    val io   = summon[Interface[PllPIO]]
    val ref  = io.field[Record]("ref")
    val loop = PllAnalog.instantiate(p.analogP)
    loop.io.refClock := ref.field[Clock]("clock")
    loop.io.refReset := ref.field[Reset]("reset")
    p.taps.foreach { n =>
      io.field[Record](n).field[Clock]("clock") := loop.io.clock
      io.field[Record](n).field[Reset]("reset") := loop.io.reset
    }

case class PllAnalogP(refHz: Int, outHz: Int, mult: Int, div: Int) extends Parameter derives ReadWriter

class PllAnalogPLayers(p: PllAnalogP) extends LayerInterface(p):
  def layers = Seq.empty
class PllAnalogPProbe(p: PllAnalogP)  extends DVBundle[PllAnalogP, PllAnalogPLayers](p)
class PllAnalogIO(p: PllAnalogP)      extends HWBundle(p):
  val refClock = Flipped(Clock())
  val refReset = Flipped(Reset())
  val clock    = Aligned(Clock())
  val reset    = Aligned(Reset())

case class PllAnalogVerilogP(REF_HZ: Int, OUT_HZ: Int, MULT: Int, DIV: Int) extends VerilogParameter

@generator
object PllAnalog extends VerilogWrapper[PllAnalogP, PllAnalogPLayers, PllAnalogIO, PllAnalogPProbe, PllAnalogVerilogP]:
  def verilogModuleName(p: PllAnalogP) = "PllAnalog"
  def verilogParameter(p:  PllAnalogP) = PllAnalogVerilogP(p.refHz, p.outHz, p.mult, p.div)
