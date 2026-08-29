// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

/** The demo SoC's test harness: everything that is not the chip, in one module the framework knows as the design's
  * testbench. It supplies the clock and reset the whole design runs on, holds the debug adapter on the JTAG pins,
  * terminates the serial pins in a console, models what the GPIO pads are wired to on the board, and logs the trace
  * every hart publishes.
  *
  * It is a container, not a monolith: each of those is its own zaozi module, instantiated here — the same shape as the
  * chip's own hierarchy. Every rate it needs comes from the settled edges, so the harness cannot disagree with the
  * design about the baud rate or the pin count.
  *
  * The adapter is [[JtagDpi]], a socket a real debugger connects to. Nothing in this design knows the debug protocol
  * any more: probe-rs walks the TAP from outside the simulation, and the harness only clocks its bits onto the pins.
  * The memory is [[DramDpi]] on the same footing: the chip's memory port ends here, in Ramulator.
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
  freqHz:     Int,
  taps:       Vector[String],
  baud:       Int,
  gpioWidth:  Int,
  jtagPort:   Int,
  tckDiv:     Int,
  memShape:   AxiShape,
  memBase:    Long,
  memHz:      Int,
  dramConfig: String,
  traces:     Vector[TraceSource])
    extends Parameter derives ReadWriter:
  require(freqHz > 0, s"clock frequency $freqHz must be positive")
  require(taps.nonEmpty, "a harness drives at least one clock tap")
  require(taps.distinct.sizeIs == taps.size, s"clock taps must be uniquely named: ${taps.mkString(", ")}")
  require(freqHz >= baud * 8, s"clock $freqHz Hz too slow for $baud baud: the console needs 8 clocks per bit")
  require(memHz > 0, s"memory port frequency $memHz must be positive")
  require(traces.map(_.hart).distinct.sizeIs == traces.size, "one trace source per hart")
  def consoleP:             ConsoleP  = ConsoleP(freqHz / baud)
  def padsP:                GpioPadsP = GpioPadsP(gpioWidth)
  def adapterP:             JtagDpiP  = JtagDpiP(jtagPort, tckDiv)
  // The DRAM's own clock comes out of its timing; what it needs from here is the period of the port it answers.
  def dramP:                DramDpiP  = DramDpiP(dramConfig, memBase, 1000000000000L / memHz, memShape)
  // A debug session runs on wall-clock time and the design keeps clocking through it, so the budget covers the whole
  // bring-up, not just the program: what a hung debugger costs is exactly this much simulation.
  def clockP:               ClockGenP = ClockGenP(freqHz, watchdogMs = 50)
  def logP(t: TraceSource): TraceLogP = TraceLogP(t.hart, t.xlen, t.regIndexBits)

class TestHarnessPLayers(p: TestHarnessP) extends LayerInterface(p):
  def layers = Seq.empty
class TestHarnessPProbe(p: TestHarnessP)  extends DVRecord[TestHarnessP, TestHarnessPLayers](p)
class TestHarnessPIO(p: TestHarnessP)     extends HWRecord(p):
  val taps       = p.taps.map(n => Aligned(n, new ClockRecord))
  val serialPins = Flipped("serialPins", new SerialRecord)
  val gpioPins   = Flipped("gpioPins", new GpioPinsRecord(p.gpioWidth))
  val jtagPins   = Flipped("jtagPins", new JtagRecord)
  // The memory port is a synchronous bus, so its clock crosses the boundary with it.
  val memPins    = Flipped("memPins", new AxiPortRecord(p.memShape))
  val memClock   = Flipped("memClock", new ClockRecord)
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

    // The debug adapter on the JTAG pins: the far end of its socket is the debugger.
    val adapter = JtagDpi.instantiate(p.adapterP)
    adapter.io.clock := gen.io.clock
    adapter.io.reset := gen.io.reset
    val jtag = io.field[Record]("jtagPins")
    jtag.field[Clock]("tck")  := adapter.io.tck
    jtag.field[Bool]("tms")   := adapter.io.tms
    jtag.field[Bool]("tdi")   := adapter.io.tdi
    jtag.field[Bool]("trstN") := adapter.io.trstN
    adapter.io.tdo            := jtag.field[Bool]("tdo")

    // The terminal on the other end of the serial line.
    val console = ConsoleGen.instantiate(p.consoleP)
    console.io.clk.clock := gen.io.clock
    console.io.clk.reset := gen.io.reset
    val serial = io.field[Record]("serialPins")
    console.io.serial.tx     := serial.field[Bool]("tx")
    serial.field[Bool]("rx") := console.io.serial.rx

    // The memory the chip's bus port ends in. It is not part of the chip and not modelled here: the beats go to
    // Ramulator, which says when each one is done.
    val dram = DramDpi.instantiate(p.dramP)
    dram.io.clk.clock := io.field[Record]("memClock").field[Clock]("clock")
    dram.io.clk.reset := io.field[Record]("memClock").field[Reset]("reset")
    // Both sides carry the same shape with the same flips, so the whole port connects at once.
    dram.io.in :<>= io.field[AxiPortRecord]("memPins")

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
