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

case class TestHarnessP(
  freqHz:          Int,
  taps:            Vector[String],
  tckTaps:         Vector[String],
  baud:            Int,
  gpioWidth:       Int,
  jtagPort:        Int,
  tckDiv:          Int,
  traces:          Vector[TraceSource])
    extends Parameter derives Writer:
  require(freqHz > 0, s"clock frequency $freqHz must be positive")
  require(taps.nonEmpty, "a harness drives at least one clock tap")
  require(taps.distinct.sizeIs == taps.size, s"clock taps must be uniquely named: ${taps.mkString(", ")}")
  require(freqHz >= baud * 8, s"clock $freqHz Hz too slow for $baud baud: the console needs 8 clocks per bit")
  require(traces.map(_.hart).distinct.sizeIs == traces.size, "one trace source per hart")
  def consoleP:             ConsoleP  = ConsoleP(freqHz / baud)
  def padsP:                GpioPadsP = GpioPadsP(gpioWidth)
  def adapterP:             JtagDpiP  = JtagDpiP(jtagPort, tckDiv)
  def clockP:               ClockGenP = ClockGenP(freqHz, watchdogMs = 50)
  def logP(t: TraceSource): TraceLogP = TraceLogP(t.hart, t.retirement.xlen, t.retirement.regIndexBits)

class TestHarnessPLayers(p: TestHarnessP) extends LayerInterface(p):
  def layers = Seq.empty
class TestHarnessPProbe(p: TestHarnessP)  extends DVRecord[TestHarnessP, TestHarnessPLayers](p)
class TestHarnessPIO(p: TestHarnessP)     extends ProbeIO(p, p.traces):
  val taps       = p.taps.map(n => Aligned(n, new ClockRecord))
  val serialPins = Flipped("serialPins", new SerialRecord)
  val gpioPins   = Flipped("gpioPins", new GpioPinsRecord(p.gpioWidth))
  val jtagPins   = Flipped("jtagPins", new JtagRecord)

  val tckPorts = p.tckTaps.map(n => Aligned(n, new ClockRecord))

@generator(inMemory = true)
object TestHarnessGen extends Generator[TestHarnessP, TestHarnessPLayers, TestHarnessPIO, TestHarnessPProbe]:
  def architecture(p: TestHarnessP) =
    val io = summon[Interface[TestHarnessPIO]]

    val gen = ClockGen.instantiate(p.clockP)
    p.taps.foreach { n =>
      io.field[Record](n).field[Clock]("clock") := gen.io.clock
      io.field[Record](n).field[Reset]("reset") := gen.io.reset
    }

    val adapter = JtagDpi.instantiate(p.adapterP)
    adapter.io.clock := gen.io.clock
    adapter.io.reset := gen.io.reset
    val jtag = io.field[Record]("jtagPins")
    jtag.field[Bool]("tms")   := adapter.io.tms
    jtag.field[Bool]("tdi")   := adapter.io.tdi
    jtag.field[Bool]("trstN") := adapter.io.trstN
    adapter.io.tdo            := jtag.field[Bool]("tdo")
    p.tckTaps.foreach { n =>
      io.field[Record](n).field[Clock]("clock") := adapter.io.tck
      io.field[Record](n).field[Reset]("reset") := gen.io.reset
    }

    val console = ConsoleGen.instantiate(p.consoleP)
    console.io.clk.clock := gen.io.clock
    console.io.clk.reset := gen.io.reset
    val serial = io.field[Record]("serialPins")
    console.io.serial.tx     := serial.field[Bool]("tx")
    serial.field[Bool]("rx") := console.io.serial.rx

    val pads = GpioPadsGen.instantiate(p.padsP)
    val gpio = io.field[Record]("gpioPins")
    pads.io.in.out         := gpio.field[Bits]("out")
    pads.io.in.oe          := gpio.field[Bits]("oe")
    gpio.field[Bits]("in") := pads.io.in.in

    p.traces.foreach { t =>
      val log   = TraceLog.instantiate(p.logP(t))
      val trace = t.trace.bind(io).read()
      log.io.clock   := trace.clock
      log.io.reset   := trace.inReset
      log.io.valid   := trace.valid
      log.io.pc      := trace.pc
      log.io.instr   := trace.instr
      log.io.rdWe    := trace.rdWe
      log.io.rd      := trace.rd
      log.io.rdWdata := trace.rdWdata
    }
