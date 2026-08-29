// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

/** The demo SoC's test harness: everything that is not the chip, in one module the framework knows as the design's
  * testbench. It supplies the clock and reset the whole design runs on, drives the JTAG pins as a debug adapter,
  * terminates the serial pins in a console, models what the GPIO pads are wired to on the board, and logs the trace
  * every hart publishes.
  *
  * It is a container, not a monolith: each of those is its own zaozi module, instantiated here — the same shape as the
  * chip's own hierarchy. Every rate it needs comes from the settled edges, so the harness cannot disagree with the
  * design about the baud rate, the pin count or how to scan the TAP.
  */

/** One leaf of a hart's trace as it arrives here: the framework named the port, the protocol named the field. */
final case class TracePort(field: String, port: String, width: Int, bool: Boolean) derives ReadWriter:
  require(width > 0, s"trace port $port has width $width")

final case class TraceSource(hart: String, xlen: Int, regIndexBits: Int, ports: Vector[TracePort]) derives ReadWriter:
  require(ports.nonEmpty, s"trace source $hart carries no leaves")
  def port(field: String): TracePort =
    val found = ports.find(_.field == field)
    require(found.isDefined, s"$hart's trace has no field '$field' (${ports.map(_.field).mkString(", ")})")
    found.get

given traceSourcesTokens: mainargs.TokensReader.Simple[Vector[TraceSource]] = jsonTokens("trace-sources")

case class TestHarnessP(
  freqHz:         Int,
  taps:           Vector[String],
  baud:           Int,
  gpioWidth:      Int,
  script:         Vector[DmiWrite],
  irLength:       Int,
  abits:          Int,
  dataBits:       Int,
  dmiInstruction: Int,
  tckDiv:         Int,
  dwell:          Int,
  traces:         Vector[TraceSource])
    extends Parameter derives ReadWriter:
  require(freqHz > 0, s"clock frequency $freqHz must be positive")
  require(taps.nonEmpty, "a harness drives at least one clock tap")
  require(taps.distinct.sizeIs == taps.size, s"clock taps must be uniquely named: ${taps.mkString(", ")}")
  require(freqHz >= baud * 8, s"clock $freqHz Hz too slow for $baud baud: the console needs 8 clocks per bit")
  require(traces.map(_.hart).distinct.sizeIs == traces.size, "one trace source per hart")
  def consoleP:             ConsoleP  = ConsoleP(freqHz / baud)
  def padsP:                GpioPadsP = GpioPadsP(gpioWidth)
  def hostP:                JtagHostP = JtagHostP(script, irLength, abits, dataBits, dmiInstruction, tckDiv, dwell)
  def clockP:               ClockGenP = ClockGenP(freqHz)
  def logP(t: TraceSource): TraceLogP = TraceLogP(t.hart, t.xlen, t.regIndexBits)

class TestHarnessPLayers(p: TestHarnessP) extends LayerInterface(p):
  def layers = Seq.empty
class TestHarnessPProbe(p: TestHarnessP)  extends DVRecord[TestHarnessP, TestHarnessPLayers](p)
class TestHarnessPIO(p: TestHarnessP)     extends HWRecord(p):
  val taps       = p.taps.map(n => Aligned(n, new ClockRecord))
  val serialPins = Flipped("serialPins", new SerialRecord)
  val gpioPins   = Flipped("gpioPins", new GpioPinsRecord(p.gpioWidth))
  val jtagPins   = Flipped("jtagPins", new JtagRecord)
  // The trace runs in the chip's clock domain, so the harness takes that clock as an ordinary edge; the leaves
  // themselves are data inputs, resolved out of the design's probes by the framework.
  val traceClock = Flipped("traceClock", new ClockRecord)
  val traceData  = p.traces.flatMap(_.ports.map(t => Flipped(t.port, if t.bool then Bool() else UInt(t.width))))

@generator
object TestHarnessGen extends Generator[TestHarnessP, TestHarnessPLayers, TestHarnessPIO, TestHarnessPProbe]:
  def architecture(p: TestHarnessP) =
    val io = summon[Interface[TestHarnessPIO]]

    // The clock and reset the chip runs on, fanned out to every tap the design asked for.
    val gen = ClockGen.instantiate(p.clockP)
    p.taps.foreach { n =>
      io.field[Record](n).field[Clock]("clock") := gen.io.clock
      io.field[Record](n).field[Reset]("reset") := gen.io.reset
    }

    // The debug adapter on the JTAG pins.
    val host = JtagHostGen.instantiate(p.hostP)
    host.io.clk.clock := gen.io.clock
    host.io.clk.reset := gen.io.reset
    val jtag = io.field[Record]("jtagPins")
    jtag.field[Clock]("tck")  := host.io.tap.tck
    jtag.field[Bool]("tms")   := host.io.tap.tms
    jtag.field[Bool]("tdi")   := host.io.tap.tdi
    jtag.field[Bool]("trstN") := host.io.tap.trstN
    host.io.tap.tdo           := jtag.field[Bool]("tdo")

    // The terminal on the other end of the serial line.
    val console = ConsoleGen.instantiate(p.consoleP)
    console.io.clk.clock := gen.io.clock
    console.io.clk.reset := gen.io.reset
    val serial = io.field[Record]("serialPins")
    console.io.serial.tx     := serial.field[Bool]("tx")
    serial.field[Bool]("rx") := console.io.serial.rx

    // What the GPIO pads are wired to on the board.
    val pads = GpioPadsGen.instantiate(p.padsP)
    val gpio = io.field[Record]("gpioPins")
    pads.io.in.out         := gpio.field[UInt]("out")
    pads.io.in.oe          := gpio.field[UInt]("oe")
    gpio.field[UInt]("in") := pads.io.in.in

    // One log per hart, clocked by the chip the trace came from.
    val traceClock = io.field[Record]("traceClock").field[Clock]("clock")
    p.traces.foreach { t =>
      val log = TraceLog.instantiate(p.logP(t))
      log.io.clock   := traceClock
      log.io.valid   := io.field[Bool](t.port("valid").port)
      log.io.pc      := io.field[UInt](t.port("pc").port)
      log.io.instr   := io.field[UInt](t.port("instr").port)
      log.io.rdWe    := io.field[Bool](t.port("rdWe").port)
      log.io.rd      := io.field[UInt](t.port("rd").port)
      log.io.rdWdata := io.field[UInt](t.port("rdWdata").port)
    }
