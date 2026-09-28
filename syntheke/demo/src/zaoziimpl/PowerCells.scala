package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.{DVBundle, HWBundle, LayerInterface, Parameter, VerilogParameter, VerilogWrapper}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bits, Bool, Clock, Reset}
import upickle.default.ReadWriter

case class LevelShifterP(width: Int, sourceMillivolts: Int, targetMillivolts: Int)
    extends Parameter derives ReadWriter:
  require(width > 0, s"level shifter width $width must be positive")
  require(sourceMillivolts > 0, s"source voltage $sourceMillivolts mV must be positive")
  require(targetMillivolts > 0, s"target voltage $targetMillivolts mV must be positive")

class LevelShifterPLayers(p: LevelShifterP) extends LayerInterface(p):
  def layers = Seq.empty
class LevelShifterPProbe(p: LevelShifterP) extends DVBundle[LevelShifterP, LevelShifterPLayers](p)
class LevelShifterIO(p: LevelShifterP) extends HWBundle(p):
  val in = Flipped(Bits(p.width))
  val out = Aligned(Bits(p.width))

case class LevelShifterVerilogP(WIDTH: Int, SOURCE_MILLIVOLTS: Int, TARGET_MILLIVOLTS: Int) extends VerilogParameter

@generator
object LevelShifter
    extends VerilogWrapper[LevelShifterP, LevelShifterPLayers, LevelShifterIO, LevelShifterPProbe, LevelShifterVerilogP]:
  def verilogModuleName(p: LevelShifterP) = "DemoLevelShifter"
  def verilogParameter(p: LevelShifterP) = LevelShifterVerilogP(p.width, p.sourceMillivolts, p.targetMillivolts)

case class IsolationP(width: Int) extends Parameter derives ReadWriter:
  require(width > 0, s"isolation width $width must be positive")

class IsolationPLayers(p: IsolationP) extends LayerInterface(p):
  def layers = Seq.empty
class IsolationPProbe(p: IsolationP) extends DVBundle[IsolationP, IsolationPLayers](p)
class IsolationIO(p: IsolationP) extends HWBundle(p):
  val in = Flipped(Bits(p.width))
  val isolate = Flipped(Bool())
  val out = Aligned(Bits(p.width))

case class IsolationVerilogP(WIDTH: Int) extends VerilogParameter

@generator
object Isolation extends VerilogWrapper[IsolationP, IsolationPLayers, IsolationIO, IsolationPProbe, IsolationVerilogP]:
  def verilogModuleName(p: IsolationP) = "DemoIsolation"
  def verilogParameter(p: IsolationP) = IsolationVerilogP(p.width)

case class PowerSwitchP(riseCycles: Int, fallCycles: Int) extends Parameter derives ReadWriter:
  require(riseCycles > 0, s"power rise delay $riseCycles must be positive")
  require(fallCycles > 0, s"power fall delay $fallCycles must be positive")

class PowerSwitchPLayers(p: PowerSwitchP) extends LayerInterface(p):
  def layers = Seq.empty
class PowerSwitchPProbe(p: PowerSwitchP) extends DVBundle[PowerSwitchP, PowerSwitchPLayers](p)
class PowerSwitchIO(p: PowerSwitchP) extends HWBundle(p):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val enable = Flipped(Bool())
  val good = Aligned(Bool())

case class PowerSwitchVerilogP(RISE_CYCLES: Int, FALL_CYCLES: Int) extends VerilogParameter

@generator
object PowerSwitch
    extends VerilogWrapper[PowerSwitchP, PowerSwitchPLayers, PowerSwitchIO, PowerSwitchPProbe, PowerSwitchVerilogP]:
  def verilogModuleName(p: PowerSwitchP) = "DemoPowerSwitch"
  def verilogParameter(p: PowerSwitchP) = PowerSwitchVerilogP(p.riseCycles, p.fallCycles)

case class RetentionP(width: Int, cpuMillivolts: Int, retentionMillivolts: Int) extends Parameter derives ReadWriter:
  require(width > 0, s"retention width $width must be positive")
  require(cpuMillivolts > 0, s"CPU voltage $cpuMillivolts mV must be positive")
  require(retentionMillivolts > 0, s"retention voltage $retentionMillivolts mV must be positive")

class RetentionPLayers(p: RetentionP) extends LayerInterface(p):
  def layers = Seq.empty
class RetentionPProbe(p: RetentionP) extends DVBundle[RetentionP, RetentionPLayers](p)
class RetentionIO(p: RetentionP) extends HWBundle(p):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val in = Flipped(Bits(p.width))
  val save = Flipped(Bool())
  val restore = Flipped(Bool())
  val out = Aligned(Bits(p.width))
  val saved = Aligned(Bool())
  val restored = Aligned(Bool())

case class RetentionVerilogP(WIDTH: Int, SOURCE_MILLIVOLTS: Int, RETENTION_MILLIVOLTS: Int) extends VerilogParameter

@generator
object RetentionCell extends VerilogWrapper[RetentionP, RetentionPLayers, RetentionIO, RetentionPProbe, RetentionVerilogP]:
  def verilogModuleName(p: RetentionP) = "DemoRetention"
  def verilogParameter(p: RetentionP) = RetentionVerilogP(p.width, p.cpuMillivolts, p.retentionMillivolts)
