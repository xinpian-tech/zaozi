// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

// ============ Gpio: a memory-mapped peripheral ============

case class GpioP(name: String, base: Long, size: Long, port: AxiShape) extends Parameter derives ReadWriter
class GpioPLayers(p: GpioP)                                            extends LayerInterface(p):
  def layers = Seq.empty
class GpioPProbe(p: GpioP)                                             extends DVRecord[GpioP, GpioPLayers](p)
class GpioPIO(p: GpioP)                                                extends HWRecord(p):
  val clk = Flipped("clk", new ClockRecord)
  val in  = Flipped("in", new AxiPortRecord(p.port))
@zaoziGenerator
object GpioGen                                                         extends Generator[GpioP, GpioPLayers, GpioPIO, GpioPProbe]:
  def architecture(p: GpioP) = summon[Interface[GpioPIO]].dontCare()
