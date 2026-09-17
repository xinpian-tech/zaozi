package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.zaozi.{DVBundle, HWBundle, LayerInterface, Parameter, VerilogParameter, VerilogWrapper}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bool, Clock, Reset}
import upickle.default.ReadWriter


case class JtagDpiP(port: Int, tckDiv: Int) extends Parameter derives ReadWriter:
  require(port > 0 && port < 65536, s"port $port must be a TCP port")
  require(tckDiv >= 2, s"tck divisor $tckDiv must be at least 2")

class JtagDpiPLayers(p: JtagDpiP) extends LayerInterface(p):
  def layers = Seq.empty
class JtagDpiPProbe(p: JtagDpiP)  extends DVBundle[JtagDpiP, JtagDpiPLayers](p)
class JtagDpiIO(p: JtagDpiP)      extends HWBundle(p):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val tck   = Aligned(Clock())
  val tms   = Aligned(Bool())
  val tdi   = Aligned(Bool())
  val trstN = Aligned(Bool())
  val tdo   = Flipped(Bool())

case class JtagDpiVerilogP(PORT: Int, TCK_DIV: Int) extends VerilogParameter

@generator
object JtagDpi extends VerilogWrapper[JtagDpiP, JtagDpiPLayers, JtagDpiIO, JtagDpiPProbe, JtagDpiVerilogP]:
  def verilogModuleName(p: JtagDpiP) = "JtagDpi"
  def verilogParameter(p:  JtagDpiP) = JtagDpiVerilogP(p.port, p.tckDiv)
