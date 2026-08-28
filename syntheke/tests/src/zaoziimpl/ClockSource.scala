// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

/** The design's clock and reset origin: one clock/reset pair per named tap. RTL cannot create a clock out of nothing —
  * this module is the declared simulation boundary where the testbench's clock enters the design, so its taps stay
  * undriven placeholder outputs.
  */

case class ClockSourceP(freqHz: Int, taps: Vector[String]) extends Parameter derives ReadWriter:
  require(freqHz > 0, s"clock frequency $freqHz must be positive")
  require(taps.nonEmpty, "a clock source needs at least one tap")

class ClockSourcePLayers(p: ClockSourceP) extends LayerInterface(p):
  def layers = Seq.empty
class ClockSourcePProbe(p: ClockSourceP)  extends DVRecord[ClockSourceP, ClockSourcePLayers](p)
class ClockSourcePIO(p: ClockSourceP)     extends HWRecord(p):
  val outs = p.taps.map(n => Aligned(n, new ClockRecord))

@zaoziGenerator
object ClockSourceGen extends Generator[ClockSourceP, ClockSourcePLayers, ClockSourcePIO, ClockSourcePProbe]:
  def architecture(p: ClockSourceP) = summon[Interface[ClockSourcePIO]].dontCare()
