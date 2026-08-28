// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

// ============ WidthBridge: wide to narrow ============

case class BridgeP(wide: AxiShape, narrow: AxiShape) extends Parameter derives ReadWriter
class BridgePLayers(p: BridgeP)                      extends LayerInterface(p):
  def layers = Seq.empty
class BridgePProbe(p: BridgeP)                       extends DVRecord[BridgeP, BridgePLayers](p)
class BridgePIO(p: BridgeP)                          extends HWRecord(p):
  val clk = Flipped("clk", new ClockRecord)
  val in  = Flipped("in", new AxiPortRecord(p.wide))
  val out = Aligned("out", new AxiPortRecord(p.narrow))
@zaoziGenerator
object BridgeGen                                     extends Generator[BridgeP, BridgePLayers, BridgePIO, BridgePProbe]:
  def architecture(p: BridgeP) = summon[Interface[BridgePIO]].dontCare()
