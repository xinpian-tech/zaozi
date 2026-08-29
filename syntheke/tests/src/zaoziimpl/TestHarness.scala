// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

/** The demo SoC's test harness: everything that is not the chip, in one module the framework knows as the design's
  * testbench. It supplies the clock and reset the whole design runs on, drives the JTAG pins as a debug adapter would,
  * terminates the serial pins in a console, and models what the GPIO pads are wired to on the board.
  *
  * It is a container, not a monolith: each of those is its own zaozi module, instantiated here — the same shape as the
  * chip's own hierarchy. Every rate it needs comes from the settled edges, so the harness cannot disagree with the
  * design about the baud rate, the pin count or how to scan the TAP.
  */

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
  dwell:          Int)
    extends Parameter derives ReadWriter:
  require(freqHz > 0, s"clock frequency $freqHz must be positive")
  require(taps.nonEmpty, "a harness drives at least one clock tap")
  require(taps.distinct.sizeIs == taps.size, s"clock taps must be uniquely named: ${taps.mkString(", ")}")
  require(freqHz >= baud * 8, s"clock $freqHz Hz too slow for $baud baud: the console needs 8 clocks per bit")
  def consoleP: ConsoleP  = ConsoleP(freqHz / baud)
  def padsP:    GpioPadsP = GpioPadsP(gpioWidth)
  def hostP:    JtagHostP = JtagHostP(script, irLength, abits, dataBits, dmiInstruction, tckDiv, dwell)
  def clockP:   ClockGenP = ClockGenP(freqHz)

class TestHarnessPLayers(p: TestHarnessP) extends LayerInterface(p):
  def layers = Seq.empty
class TestHarnessPProbe(p: TestHarnessP)  extends DVRecord[TestHarnessP, TestHarnessPLayers](p)
class TestHarnessPIO(p: TestHarnessP)     extends HWRecord(p):
  val taps   = p.taps.map(n => Aligned(n, new ClockRecord))
  val serial = Flipped("serialPins", new SerialRecord)
  val gpio   = Flipped("gpioPins", new GpioPinsRecord(p.gpioWidth))
  val jtag   = Flipped("jtagPins", new JtagRecord)

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
