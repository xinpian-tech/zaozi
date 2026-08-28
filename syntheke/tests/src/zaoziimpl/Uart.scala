// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

// ============ Uart: a memory-mapped peripheral ============

case class UartP(name: String, base: Long, size: Long, port: AxiShape) extends Parameter derives ReadWriter
class UartPLayers(p: UartP)                                            extends LayerInterface(p):
  def layers = Seq.empty
class UartPProbe(p: UartP)                                             extends DVRecord[UartP, UartPLayers](p)
class UartPIO(p: UartP)                                                extends HWRecord(p):
  val clk    = Flipped("clk", new ClockRecord)
  val in     = Flipped("in", new AxiPortRecord(p.port))
  val serial = Aligned("serial", new SerialRecord)
@zaoziGenerator
object UartGen                                                         extends Generator[UartP, UartPLayers, UartPIO, UartPProbe]:
  def architecture(p: UartP) = summon[Interface[UartPIO]].dontCare()
