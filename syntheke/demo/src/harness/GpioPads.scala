package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.zaozi.{DVBundle, Generator, HWBundle, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter


case class GpioPadsP(width: Int) extends Parameter derives ReadWriter:
  require(width >= 1 && width <= 32, s"gpio pads width $width must be within 1..32")

class GpioPadsPLayers(p: GpioPadsP) extends LayerInterface(p):
  def layers = Seq.empty
class GpioPadsPProbe(p: GpioPadsP)  extends DVBundle[GpioPadsP, GpioPadsPLayers](p)
class GpioPadsPIO(p: GpioPadsP)     extends HWBundle(p):
  val in = Flipped(new GpioPinsBundle(p.width))

@zaoziGenerator
object GpioPadsGen extends Generator[GpioPadsP, GpioPadsPLayers, GpioPadsPIO, GpioPadsPProbe]:
  override def moduleName(p: GpioPadsP): String = s"GpioPads_${p.hashCode.toHexString}"

  def architecture(p: GpioPadsP) =
    val io = summon[Interface[GpioPadsPIO]]
    io.in.in := io.in.out & io.in.oe
