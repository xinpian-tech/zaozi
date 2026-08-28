// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.{Bool, Record}
import upickle.default.ReadWriter

/** The pad ring terminating a serial pin pair, wired as a test-mode loopback jumper: rx echoes tx, so the enclosed
  * design is self-contained and simulable — software sends a byte and receives it back. A production pad ring would
  * route both lines to package pins instead.
  */

case class SerialPadsP(baud: Int) extends Parameter derives ReadWriter:
  require(baud > 0, s"baud $baud must be positive")

class SerialPadsPLayers(p: SerialPadsP) extends LayerInterface(p):
  def layers = Seq.empty
class SerialPadsPProbe(p: SerialPadsP)  extends DVRecord[SerialPadsP, SerialPadsPLayers](p)
class SerialPadsPIO(p: SerialPadsP)     extends HWRecord(p):
  val in = Flipped("in", new SerialRecord)

@zaoziGenerator
object SerialPadsGen extends Generator[SerialPadsP, SerialPadsPLayers, SerialPadsPIO, SerialPadsPProbe]:
  def architecture(p: SerialPadsP) =
    val io = summon[Interface[SerialPadsPIO]]
    io.field[Record]("in").field[Bool]("rx") := io.field[Record]("in").field[Bool]("tx")
