// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

// ============ Dma: a bus-mastering DMA engine ============

case class DmaP(name: String, idBits: Int, maxFlight: Int, port: AxiShape) extends Parameter derives ReadWriter
class DmaPLayers(p: DmaP)                                                  extends LayerInterface(p):
  def layers = Seq.empty
class DmaPProbe(p: DmaP)                                                   extends DVRecord[DmaP, DmaPLayers](p)
class DmaPIO(p: DmaP)                                                      extends HWRecord(p):
  val clk = Flipped("clk", new ClockRecord)
  val mem = Aligned("mem", new AxiPortRecord(p.port))
@zaoziGenerator
object DmaGen                                                              extends Generator[DmaP, DmaPLayers, DmaPIO, DmaPProbe]:
  def architecture(p: DmaP) = summon[Interface[DmaPIO]].dontCare()
