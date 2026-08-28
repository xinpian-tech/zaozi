// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

// ============ Core: an AXI master with a local id space ============

case class CoreP(name: String, idBits: Int, maxFlight: Int, port: AxiShape) extends Parameter derives ReadWriter
class CorePLayers(p: CoreP)                                                 extends LayerInterface(p):
  def layers = Seq.empty
class CorePProbe(p: CoreP)                                                  extends DVRecord[CoreP, CorePLayers](p)
class CorePIO(p: CoreP)                                                     extends HWRecord(p):
  val clk = Flipped("clk", new ClockRecord)
  val mem = Aligned("mem", new AxiPortRecord(p.port))
@zaoziGenerator
object CoreGen                                                              extends Generator[CoreP, CorePLayers, CorePIO, CorePProbe]:
  def architecture(p: CoreP) = summon[Interface[CorePIO]].dontCare()
