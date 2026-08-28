// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

// ============ DRAM: the uncached memory slave ============

case class DramP(ranks: Int, port: AxiShape) extends Parameter derives ReadWriter
class DramPLayers(p: DramP)                  extends LayerInterface(p):
  def layers = Seq.empty
class DramPProbe(p: DramP)                   extends DVRecord[DramP, DramPLayers](p)
class DramPIO(p: DramP)                      extends HWRecord(p):
  val clk = Flipped("clk", new ClockRecord)
  val in  = Flipped("in", new AxiPortRecord(p.port))
@zaoziGenerator
object DramGen                               extends Generator[DramP, DramPLayers, DramPIO, DramPProbe]:
  def architecture(p: DramP) = summon[Interface[DramPIO]].dontCare()
