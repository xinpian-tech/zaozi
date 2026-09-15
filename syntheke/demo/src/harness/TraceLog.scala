package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.zaozi.{DVBundle, HWBundle, LayerInterface, Parameter, VerilogParameter, VerilogWrapper}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bool, Clock, UInt}
import upickle.default.ReadWriter


case class TraceLogP(hart: String, xlen: Int, regIndexBits: Int) extends Parameter derives ReadWriter:
  require(hart.nonEmpty, "a trace log is named after the hart it follows")
  require(xlen > 0, s"xlen $xlen must be positive")
  require(regIndexBits > 0, s"register index width $regIndexBits must be positive")

class TraceLogPLayers(p: TraceLogP) extends LayerInterface(p):
  def layers = Seq.empty
class TraceLogPProbe(p: TraceLogP)  extends DVBundle[TraceLogP, TraceLogPLayers](p)
class TraceLogIO(p: TraceLogP)      extends HWBundle(p):
  val clock   = Flipped(Clock())
  val reset   = Flipped(Bool())
  val valid   = Flipped(Bool())
  val pc      = Flipped(UInt(p.xlen))
  val instr   = Flipped(UInt(32))
  val rdWe    = Flipped(Bool())
  val rd      = Flipped(UInt(p.regIndexBits))
  val rdWdata = Flipped(UInt(p.xlen))

case class TraceLogVerilogP(HART: String, XLEN: Int, REG_INDEX_BITS: Int) extends VerilogParameter

@generator
object TraceLog extends VerilogWrapper[TraceLogP, TraceLogPLayers, TraceLogIO, TraceLogPProbe, TraceLogVerilogP]:
  def verilogModuleName(p: TraceLogP) = "TraceLog"
  def verilogParameter(p:  TraceLogP) = TraceLogVerilogP(p.hart, p.xlen, p.regIndexBits)
