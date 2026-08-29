// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

/** The UART demo's test harness: the clock the design runs on, and the loopback jumper the serial pins are wired to.
  * Same role as [[TestHarnessGen]], sized to a design that has only those two things outside it.
  */

case class UartHarnessP(freqHz: Int, taps: Vector[String], baud: Int) extends Parameter derives ReadWriter:
  require(freqHz > 0, s"clock frequency $freqHz must be positive")
  require(taps.nonEmpty, "a harness drives at least one clock tap")
  require(taps.distinct.sizeIs == taps.size, s"clock taps must be uniquely named: ${taps.mkString(", ")}")
  def padsP:  SerialPadsP = SerialPadsP(baud)
  def clockP: ClockGenP   = ClockGenP(freqHz)

class UartHarnessPLayers(p: UartHarnessP) extends LayerInterface(p):
  def layers = Seq.empty
class UartHarnessPProbe(p: UartHarnessP)  extends DVRecord[UartHarnessP, UartHarnessPLayers](p)
class UartHarnessPIO(p: UartHarnessP)     extends HWRecord(p):
  val taps   = p.taps.map(n => Aligned(n, new ClockRecord))
  val serial = Flipped("serialPins", new SerialRecord)

@generator
object UartHarnessGen extends Generator[UartHarnessP, UartHarnessPLayers, UartHarnessPIO, UartHarnessPProbe]:
  def architecture(p: UartHarnessP) =
    val io = summon[Interface[UartHarnessPIO]]

    val gen = ClockGen.instantiate(p.clockP)
    p.taps.foreach { n =>
      io.field[Record](n).field[Clock]("clock") := gen.io.clock
      io.field[Record](n).field[Reset]("reset") := gen.io.reset
    }

    val pads   = SerialPadsGen.instantiate(p.padsP)
    val serial = io.field[Record]("serialPins")
    val padsIn = pads.io.field[Record]("in")
    padsIn.field[Bool]("tx") := serial.field[Bool]("tx")
    serial.field[Bool]("rx") := padsIn.field[Bool]("rx")
