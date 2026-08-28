// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

// ============ L2: a pass-through adapter with its own writeback master ============

case class L2P(capacityKiB: Int, up: AxiShape, down: AxiShape) extends Parameter derives ReadWriter
class L2PLayers(p: L2P)                                        extends LayerInterface(p):
  def layers = Seq.empty
class L2PProbe(p: L2P)                                         extends DVRecord[L2P, L2PLayers](p)
class L2PIO(p: L2P)                                            extends HWRecord(p):
  val in  = Flipped("in", new AxiPortRecord(p.up))
  val out = Aligned("out", new AxiPortRecord(p.down))
@zaoziGenerator
object L2Gen                                                   extends Generator[L2P, L2PLayers, L2PIO, L2PProbe]:
  def architecture(p: L2P) = summon[Interface[L2PIO]].dontCare()
