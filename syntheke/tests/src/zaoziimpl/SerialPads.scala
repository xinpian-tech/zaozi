// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

/** The pad ring terminating a serial pin pair: the design boundary where tx leaves and rx enters. Like the clock
  * source, the pad side has no in-design driver — the rx placeholder marks where the testbench drives the line.
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
  def architecture(p: SerialPadsP) = summon[Interface[SerialPadsPIO]].dontCare()
