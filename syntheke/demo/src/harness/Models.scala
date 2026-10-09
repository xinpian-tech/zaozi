package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.zaozi.*
import me.jiuyang.syntheke.demo.InstructionRetirement
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.Writer


final case class TraceSource(
  hart:         String,
  retirement:   InstructionRetirement,
  trace:        Probe[InstructionTrace])
    derives Writer

case class BoardP(
  freqHz:          Int,
  tckTaps:         Vector[String],
  baud:            Int,
  pinCount:        Int,
  uartPins:        Vector[Int],
  jtagPins:        Vector[Int],
  jtagPort:        Int,
  tckDiv:          Int)
    extends Parameter derives Writer:
  require(freqHz > 0, s"clock frequency $freqHz must be positive")
  require(freqHz >= baud * 8, s"clock $freqHz Hz too slow for $baud baud: the console needs 8 clocks per bit")
  def adapterP:             JtagDpiP  = JtagDpiP(jtagPort, tckDiv)

class BoardPLayers(p: BoardP) extends LayerInterface(p):
  def layers = Seq.empty

class BoardPProbe(p: BoardP)  extends DVRecord[BoardP, BoardPLayers](p)
class BoardPIO(p: BoardP)     extends HWRecord(p):
  val clk = Flipped("clk", new ClockRecord)
  val serial = Aligned("serial", new SerialBundle)
  val pins = Vector.tabulate(p.pinCount)(i => Flipped(s"pin$i", new IORecord))

  val tckPorts = p.tckTaps.map(n => Aligned(n, new ClockRecord))

@generator(inMemory = true)
object BoardGen extends Generator[BoardP, BoardPLayers, BoardPIO, BoardPProbe]:
  def architecture(p: BoardP) =
    val io = summon[Interface[BoardPIO]]

    val clk = io.field[Record]("clk")
    val adapter = JtagDpi.instantiate(p.adapterP)
    adapter.io.clock := clk.field[Clock]("clock")
    adapter.io.reset := clk.field[Reset]("reset")
    p.tckTaps.foreach { n =>
      io.field[Record](n).field[Clock]("clock") := adapter.io.tck
      io.field[Record](n).field[Reset]("reset") := clk.field[Reset]("reset")
    }

    val pads = Vector.tabulate(p.pinCount) { i =>
      val pad = IOPadGen.instantiate(IOPadP())
      val pin = io.field[Record](s"pin$i")
      pad.io.pin.inputEnable := pin.field[Bool]("inputEnable")
      pad.io.pin.outputValue := pin.field[Bool]("outputValue")
      pad.io.pin.outputEnable := pin.field[Bool]("outputEnable")
      pin.field[Bool]("inputValue") := pad.io.pin.inputValue
      pad.io.external := (
        if i == p.uartPins(0) then true.B
        else if i == p.uartPins(1) then io.field[SerialBundle]("serial").rx
        else if i == p.jtagPins(0) then adapter.io.tms
        else if i == p.jtagPins(1) then adapter.io.tdi
        else if i == p.jtagPins(2) then adapter.io.trstN
        else false.B
      )
      pad
    }
    io.field[SerialBundle]("serial").tx := pads(p.uartPins(0)).io.value
    adapter.io.tdo := pads(p.jtagPins(3)).io.value


case class TraceMonitorP(source: TraceSource) extends Parameter derives Writer
class TraceMonitorLayers(p: TraceMonitorP) extends LayerInterface(p):
  def layers = Seq.empty

class TraceMonitorProbe(p: TraceMonitorP) extends DVRecord[TraceMonitorP, TraceMonitorLayers](p)
class TraceMonitorIO(p: TraceMonitorP) extends ProbeIO(p, p.source.trace)

@generator(inMemory = true)
object TraceMonitorGen extends Generator[TraceMonitorP, TraceMonitorLayers, TraceMonitorIO, TraceMonitorProbe]:
  def architecture(p: TraceMonitorP) =
    val io = summon[Interface[TraceMonitorIO]]
    val t = p.source
    val log = TraceLog.instantiate(TraceLogP(t.hart, t.retirement.xlen, t.retirement.regIndexBits))
    val trace = t.trace.bind(io).read()
    log.io.clock := trace.clock
    log.io.reset := trace.inReset
    log.io.valid := trace.valid
    log.io.pc := trace.pc
    log.io.instr := trace.instr
    log.io.rdWe := trace.rdWe
    log.io.rd := trace.rd
    log.io.rdWdata := trace.rdWdata

case class OscillatorP(freqHz: Int, taps: Vector[String]) extends Parameter derives Writer
class OscillatorLayers(p: OscillatorP) extends LayerInterface(p):
  def layers = Seq.empty

class OscillatorProbe(p: OscillatorP) extends DVRecord[OscillatorP, OscillatorLayers](p)
class OscillatorIO(p: OscillatorP) extends HWRecord(p):
  val taps = p.taps.map(n => Aligned(n, new ClockRecord))

@generator(inMemory = true)
object OscillatorGen extends Generator[OscillatorP, OscillatorLayers, OscillatorIO, OscillatorProbe]:
  def architecture(p: OscillatorP) =
    val io = summon[Interface[OscillatorIO]]
    val gen = ClockGen.instantiate(ClockGenP(p.freqHz, 50))
    p.taps.foreach { name =>
      io.field[Record](name).field[Clock]("clock") := gen.io.clock
      io.field[Record](name).field[Reset]("reset") := gen.io.reset
    }
