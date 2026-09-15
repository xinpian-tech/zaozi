package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

case class ClockBufferP() extends Parameter derives ReadWriter

class ClockBufferPLayers(p: ClockBufferP) extends LayerInterface(p):
  def layers = Seq.empty
class ClockBufferPProbe(p: ClockBufferP) extends DVBundle[ClockBufferP, ClockBufferPLayers](p)
class ClockBufferPIO(p: ClockBufferP) extends HWBundle(p):
  val in = Flipped(new ClockBundle)
  val out = Aligned(new ClockBundle)

@generator
object ClockBufferGen extends Generator[ClockBufferP, ClockBufferPLayers, ClockBufferPIO, ClockBufferPProbe]:
  def architecture(p: ClockBufferP) =
    val io = summon[Interface[ClockBufferPIO]]
    io.out.clock := io.in.clock
    io.out.reset := io.in.reset
